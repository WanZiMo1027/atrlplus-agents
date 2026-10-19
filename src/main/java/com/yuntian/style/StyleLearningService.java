package com.yuntian.style;

import com.yuntian.context.ContextSummaryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.yuntian.style.StyleTypes.*;

/** 轮询只发现工作，专用有界线程池执行模型调用，绝不进入 Agent 聊天线程。 */
public class StyleLearningService {
    private static final Logger log = LoggerFactory.getLogger(StyleLearningService.class);
    private final StyleProperties properties;
    private final ContextSummaryStore source;
    private final StyleSourceReader reader;
    private final StyleExtractor extractor;
    private final StyleEmbeddingClient embedding;
    private final StyleRepository repository;
    private final ExecutorService workers;
    public StyleLearningService(StyleProperties properties, ContextSummaryStore source, StyleSourceReader reader,
            StyleExtractor extractor, StyleEmbeddingClient embedding, StyleRepository repository, ExecutorService workers) {
        this.properties = properties; this.source = source; this.reader = reader; this.extractor = extractor;
        this.embedding = embedding; this.repository = repository; this.workers = workers;
    }
    @Scheduled(fixedDelayString = "${agent.style-learning.poll-interval:30s}")
    public void poll() {
        for (String conversation : properties.getConversations()) {
            try {
                enqueue(conversation, false);
                repository.ensureEmbeddingJobs(conversation, embedding.requestProfile());
            } catch (Exception failure) {
                log.warn("风格学习扫描暂不可用，下一轮重试: code={}", errorCode(failure));
            }
        }
        dispatch();
    }
    public Enqueued enqueue(String conversation, boolean force) {
        properties.requireConversation(conversation);
        var snapshot = source.readForAdmin(conversation).orElseThrow(() -> new NoSuchElementException("STYLE_CONVERSATION_NOT_FOUND"));
        var checkpoint = repository.checkpoint(conversation);
        if (checkpoint.cursor() == 0 && checkpoint.backfillThrough() == 0) {
            repository.initializeBackfill(conversation, snapshot.nextSequence());
            checkpoint = repository.checkpoint(conversation);
        }
        var current = repository.currentJob(conversation, checkpoint.cursor());
        if (current.isPresent()) { if (force) dispatch(); return current.get(); }
        if (snapshot.nextSequence() < checkpoint.cursor()) throw new IllegalStateException("STYLE_SOURCE_REWOUND");
        Window window = reader.window(snapshot, checkpoint.cursor());
        if (window.throughSequence() == checkpoint.cursor()) return new Enqueued(null, "idle");
        repository.pending(conversation);
        // 已存在的历史积压首次立即回填；重新启动后也用持久化时间恢复等待条件。
        boolean due = force || checkpoint.fresh() || checkpoint.cursor() < checkpoint.backfillThrough() || reader.thresholdReached(snapshot, checkpoint.cursor())
                || (checkpoint.pendingSince() != null && !checkpoint.pendingSince().plus(properties.getMaxWait()).isAfter(Instant.now()));
        if (!due) return new Enqueued(null, "waiting");
        try { window = extractor.fit(window); }
        catch (IllegalStateException tooLarge) { /* 仍登记完整窗口，让预算失败可见且可人工重试，不能悄悄跳过原文。 */ }
        Enqueued result = repository.enqueue(conversation, window);
        if (force) dispatch();
        return result;
    }
    private void dispatch() {
        for (int i = 0; i < 2; i++) {
            try { workers.execute(this::drain); }
            catch (RejectedExecutionException full) { /* 队列已满的工作保留在数据库，下轮领取，禁止 CallerRuns。 */ }
        }
    }
    public void drain() {
        for (int i = 0; i < 8 && !Thread.currentThread().isInterrupted(); i++) {
            Optional<Job> next;
            try { next = repository.claim(properties.getConversations(), properties.getExtractorVersion(), embedding.requestProfile()); }
            catch (Exception unavailable) { log.warn("风格学习任务暂无法领取"); return; }
            if (next.isEmpty()) return;
            Job job = next.get();
            try {
                if (job.kind().equals("extract")) {
                    var window = repository.decode(job.payload(), Window.class);
                    // 没有新增本人文字时不可能产生新的文字回应范例；媒体原文仍保留给下一窗口。
                    var result = window.effective() == 0 ? new Extraction(List.of(), 0, 0, 0)
                            : extractor.extract(window, () -> repository.renew(job));
                    repository.completeExtraction(job, result, embedding.requestProfile());
                } else {
                    var fragment = repository.decode(job.payload(), Fragment.class);
                    var vector = embedding.embed(fragment.embeddingText());
                    repository.completeEmbedding(job, vector);
                }
            } catch (Exception failure) {
                String code = errorCode(failure);
                try { repository.fail(job, code); } catch (Exception unavailable) { /* 租约过期后可被其他实例接管 */ }
                log.warn("风格学习任务失败: jobId={}, code={}", job.id(), code);
            }
        }
    }
    public Status status(String conversation) {
        properties.requireConversation(conversation); return repository.status(conversation, embedding.requestProfile());
    }
    public FragmentPage fragments(String conversation, long after, int limit) {
        return fragments(conversation, after, limit, null, "", null, null);
    }
    public Catalog catalog() { return repository.catalog(); }
    public FragmentPage fragments(String conversation, long after, int limit, String version, String query,
                                  String intent, String seriousness) {
        properties.requireConversation(conversation);
        String selected = version == null ? properties.getExtractorVersion() : version;
        if (after < 0 || limit < 1 || limit > 100 || selected.isBlank() || selected.length() > 128
                || query == null || query.length() > 200 || (intent != null && !INTENTS.contains(intent))
                || (seriousness != null && !SERIOUSNESS.contains(seriousness))) throw new IllegalArgumentException("STYLE_INVALID_PAGE");
        return repository.fragments(conversation, after, limit, embedding.requestProfile(), selected, query.strip(), intent, seriousness);
    }
    public List<SearchHit> search(SearchRequest request) {
        properties.requireConversation(request.conversationId());
        if (request.text() == null || request.text().isBlank() || request.text().length() > 3000
                || (request.intent() != null && !INTENTS.contains(request.intent()))
                || (request.seriousness() != null && !SERIOUSNESS.contains(request.seriousness()))
                || (request.limit() != null && (request.limit() < 1 || request.limit() > 20))) throw new IllegalArgumentException("STYLE_INVALID_SEARCH");
        // 调试端显式提交场景描述，不额外调用聊天模型改写查询，不注入正式回复。
        String query = "场景：" + request.text()
                + (request.intent() == null ? "" : "\n意图：" + request.intent())
                + (request.seriousness() == null ? "" : "\n认真程度：" + request.seriousness());
        var vector = embedding.embed(query);
        repository.verifyVectorProfile(embedding.requestProfile(), vector);
        return repository.search(request, vector);
    }
    public Enqueued retry(UUID id) {
        var result = repository.retry(id, properties.getConversations()); dispatch(); return result;
    }
    private static String errorCode(Exception failure) {
        String message = failure.getMessage();
        return message != null && message.matches("STYLE_[A-Z0-9_]+") ? message : "STYLE_TASK_FAILED";
    }
}
