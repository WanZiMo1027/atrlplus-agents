package com.yuntian.context;

import com.yuntian.dto.AgentChatRequest;
import com.yuntian.dto.AgentChatResponse;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import com.yuntian.style.StyleExampleRenderer;

/** 同步 Agent 调用的协调层：接收原文 → 预算/增量摘要 → 临时视图 → 提交业务决策。 */
@Component
@Slf4j
public class ContextManager {
    public static final AgentChatResponse SILENT = new AgentChatResponse(false, List.of(), null, null);
    private final ContextSummaryStore store;
    private final ContextCompactor compactor;
    private final ContextProperties properties;
    private final ContextTokenEstimator tokens;
    private final ContextMessageFormatter formatter;
    private final ContextPrompt prompt;
    // QQAssistant 是同步接口；缓存的 ChatMemory 只访问当前线程会话，不保存任何共享的本轮状态。
    private final ThreadLocal<Session> current = new ThreadLocal<>();

    public ContextManager(ContextSummaryStore store, ContextCompactor compactor, ContextProperties properties,
            ContextTokenEstimator tokens, ContextMessageFormatter formatter, ContextPrompt prompt) {
        properties.validate();
        this.store = store;
        this.compactor = compactor;
        this.properties = properties;
        this.tokens = tokens;
        this.formatter = formatter;
        this.prompt = prompt;
    }

    public Session open(AgentChatRequest request) {
        if (current.get() != null) throw new IllegalStateException("不能嵌套 Agent 会话");
        if (request == null || request.messages() == null || request.messages().isEmpty()) {
            throw new IllegalArgumentException("聊天批次不能为空");
        }
        var lease = store.tryAcquire(request.conversationId(), properties.getLeaseDuration())
                .orElseThrow(() -> new ConversationBusyException());
        try {
            var before = store.read(lease);
            var begin = store.beginBatch(lease, before.revision(), request.messages());
            Session session = new Session(request, lease, begin);
            if (begin.decision() == null) {
                var snapshot = store.read(lease);
                // 新租约下，旧请求留下的未完成批次按静默业务兜底封存，保留全部群友原文。
                // 这不是声称模型曾选择沉默；也不会产生机器人发言，避免旧悬挂批次永久阻塞摘要前缀。
                for (var batch : snapshot.batches()) {
                    if (batch.sequence() < begin.sequence() && !batch.completed()) {
                        session.revision = store.completeBatch(lease, session.revision, batch.sequence(), SILENT);
                        log.warn("封存旧租约未完成批次: conversationId={}, sequence={}", lease.conversationId(), batch.sequence());
                    }
                }
            }
            current.set(session);
            return session;
        } catch (RuntimeException failure) {
            release(lease);
            throw failure;
        }
    }

    public Session session(Object memoryId) {
        Session session = current.get();
        if (session == null || !session.lease.conversationId().equals(Objects.toString(memoryId))) {
            throw new IllegalStateException("ChatMemory 必须在对应 conversationId 的受管调用中使用");
        }
        return session;
    }

    /** 最终请求已经包含 LangChain4j 的输出协议说明；超过预算时根本不调用主模型。 */
    public ChatRequest guard(ChatRequest request, Object memoryId) {
        var session = session(memoryId);
        long estimate = tokens.total(request.messages())
                + tokens.text(request.responseFormat() == null ? "" : request.responseFormat().toString());
        if(estimate>properties.getTokenBudget() && session.view!=null && !session.view.styleExamples().isEmpty()) {
            // 框架追加协议后仍可能变大；先移除整块样本，再检查基础请求，不能为可选风格牺牲回复。
            String examples=session.view.styleExamples();
            request=request.toBuilder().messages(request.messages().stream().map(m->m instanceof SystemMessage value
                    ? (ChatMessage)SystemMessage.from(prompt.withoutStyle(value.text(),examples)):m).toList()).build();
            session.attachStyleExamples(List.of());
            estimate=tokens.total(request.messages())+tokens.text(request.responseFormat()==null?"":request.responseFormat().toString());
        }
        if (estimate > properties.getTokenBudget()
                || (request.toolSpecifications() != null && !request.toolSpecifications().isEmpty())) {
            throw new ContextTokenEstimator.BudgetExceededException("最终 Agent 请求超过上下文预算");
        }
        session.renew();
        session.actualEstimatedTokens = estimate;
        log.info("风格参考采用: conversationId={}, count={}, fragmentIds={}",memoryId,
                session.view==null?0:session.view.styleFragmentIds().size(),session.view==null?List.of():session.view.styleFragmentIds());
        var parameters = request.parameters().overrideWith(ChatRequestParameters.builder()
                .maxOutputTokens(properties.getOutputReservedTokens()).build());
        return request.toBuilder().parameters(parameters).build();
    }

    private void release(ContextSummaryStore.Lease lease) {
        try { store.release(lease); }
        catch (RuntimeException failure) { log.warn("释放上下文租约失败，等待到期: conversationId={}", lease.conversationId()); }
    }

    public final class Session implements AutoCloseable {
        private final AgentChatRequest request;
        private final ContextSummaryStore.Lease lease;
        private final ContextSummaryStore.BeginResult begin;
        private long revision;
        private View view;
        private SystemMessage system;
        private long actualEstimatedTokens;
        private boolean closed;
        private List<ContextSummaryStore.Batch> styleHistory = List.of();

        private Session(AgentChatRequest request, ContextSummaryStore.Lease lease, ContextSummaryStore.BeginResult begin) {
            this.request = request;
            this.lease = lease;
            this.begin = begin;
            this.revision = begin.revision();
        }

        public AgentChatResponse replayedDecision() { return begin.decision(); }
        public long sequence() { return begin.sequence(); }
        public long actualEstimatedTokens() { return actualEstimatedTokens; }

        public void renew() {
            if (closed || !store.renew(lease, properties.getLeaseDuration())) throw new ContextSummaryStore.LeaseLostException();
        }

        private List<ContextSummaryStore.Batch> history(ContextSummaryStore.Snapshot snapshot) {
            return snapshot.activeBatches().stream().filter(batch -> batch.sequence() < begin.sequence()).toList();
        }

        /** 按 QQ 消息数量从后向前累计，保留完整批次及相应决策，不能把 20 个批次误当 20 条消息。 */
        private int compactablePrefix(List<ContextSummaryStore.Batch> history) {
            int count = request.messages().size();
            int prefix = history.size();
            while (prefix > 0 && count < properties.getKeepRecentMessages()) {
                count += history.get(--prefix).messages().size();
            }
            return prefix;
        }

        private List<ChatMessage> rendered(List<ContextSummaryStore.Batch> batches) {
            return batches.stream().map(batch -> (ChatMessage) UserMessage.from(formatter.history(batch))).toList();
        }

        private long estimate(String summary, String l1, List<ChatMessage> history, List<Content> contents) {
            return estimate(summary,l1,history,contents,"");
        }
        private long estimate(String summary,String l1,List<ChatMessage> history,List<Content> contents,String examples) {
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(SystemMessage.from(prompt.render(summary, l1, examples)));
            messages.addAll(history);
            messages.add(UserMessage.from(contents));
            // 这里尚未包含框架追加的 JSON 格式说明，预先另留展开余量；guard 会按完整请求重新核算。
            return tokens.total(messages) + properties.getProtocolReservedTokens();
        }

        public View prepare(String longTermMemory) {
            renew();
            var snapshot = store.read(lease);
            String l1 = tokens.limit(longTermMemory, properties.getMaxLongTermMemoryTokens());
            List<Content> contents = formatter.current(request.messages());
            boolean degraded = false;
            // 成功一次只推进一个连续前缀，下一次只处理新游标之后的数据；每次模型调用前后都重新验证租约。
            for (int i = 0; properties.isEnabled() && i < properties.getMaxCompactionsPerRequest(); i++) {
                var history = history(snapshot);
                if (estimate(snapshot.summary(), l1, rendered(history), contents) < properties.getCompressionThreshold()) break;
                int prefix = compactablePrefix(history);
                if (prefix == 0) break;
                var selected = compactor.select(snapshot.summary(), history.subList(0, prefix));
                if (selected.isEmpty()) { degraded = true; break; }
                try {
                    String updated = compactor.compact(snapshot.summary(), selected, this::renew);
                    revision = store.commitSummary(lease, revision, snapshot.summaryVersion(),
                            snapshot.lastCompressedSequence(), selected.get(selected.size() - 1).sequence(), updated);
                } catch (ContextSummaryStore.LeaseLostException | ContextSummaryStore.StateConflictException
                        | ContextSummaryStore.CommitOutcomeUnknownException failure) {
                    throw failure;
                } catch (RuntimeException failure) {
                    // 摘要生成失败时不推进游标、不删除原文；下一轮仍能重试同一待压缩前缀。
                    log.warn("上下文压缩失败，使用有预算上限的临时视图: conversationId={}, type={}",
                            lease.conversationId(), failure.getClass().getSimpleName());
                    degraded = true;
                    break;
                }
                snapshot = store.read(lease);
            }
            var batches = new ArrayList<>(history(snapshot));
            String summary = snapshot.summary();
            long cost = estimate(summary, l1, rendered(batches), contents);
            if (cost > properties.getTokenBudget()) {
                // 先缩减检索背景，再移除必留批次之前的旧视图；Redis 原文及摘要游标均不受影响。
                l1 = tokens.limit(l1, (int) Math.max(0, tokens.text(l1) - (cost - properties.getTokenBudget())));
                cost = estimate(summary, l1, rendered(batches), contents);
                int removable = compactablePrefix(batches);
                while (cost > properties.getTokenBudget() && removable-- > 0) {
                    batches.remove(0);
                    cost = estimate(summary, l1, rendered(batches), contents);
                }
                degraded = true;
            }
            view = new View(summary, l1, rendered(batches), contents, cost,
                    cost <= properties.getTokenBudget(), degraded, snapshot.lastCompressedSequence(),"",List.of());
            // 留下已读快照的原始窗口供场景识别；只返回原批次，不渲染其中的机器人计划。
            var originals=snapshot.batches().stream().filter(b->b.sequence()<begin.sequence()).toList();
            int count=0,start=originals.size();
            while(start>0 && count<24) count+=originals.get(--start).messages().size();
            styleHistory=List.copyOf(originals.subList(start,originals.size()));
            log.info("上下文预算: conversationId={}, estimated={}, budget={}, summaryVersion={}, degraded={}, callable={}",
                    lease.conversationId(), cost, properties.getTokenBudget(), snapshot.summaryVersion(), degraded, view.callable());
            return view;
        }

        public List<ContextSummaryStore.Batch> recentOriginalBatches() { return styleHistory; }
        public View attachStyleExamples(List<StyleExampleRenderer.Example> input) {
            if(view==null||!view.callable()) return view;
            var chosen=new ArrayList<>(input);
            String examples=StyleExampleRenderer.render(chosen);
            long cost=estimate(view.summary(),view.longTermMemory(),view.history(),view.current(),examples);
            while(cost>properties.getTokenBudget()&&!chosen.isEmpty()) {
                chosen.remove(chosen.size()-1);examples=StyleExampleRenderer.render(chosen);
                cost=estimate(view.summary(),view.longTermMemory(),view.history(),view.current(),examples);
            }
            // 只改变临时模型视图，不触碰原文、摘要或持久化游标。
            view=new View(view.summary(),view.longTermMemory(),view.history(),view.current(),cost,view.callable(),view.degraded(),
                    view.compressedThrough(),examples,chosen.stream().map(StyleExampleRenderer.Example::id).toList());
            return view;
        }

        public void acceptSystem(SystemMessage message) { system = message; }

        public List<ChatMessage> memoryMessages() {
            if (view == null || system == null) throw new IllegalStateException("受管上下文尚未准备完成");
            List<ChatMessage> messages = new ArrayList<>();
            messages.add(system);
            messages.addAll(view.history());
            return messages;
        }

        public void complete(AgentChatResponse normalized) {
            revision = store.completeBatch(lease, revision, begin.sequence(), normalized);
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            current.remove();
            release(lease);
        }
    }

    public record View(String summary, String longTermMemory, List<ChatMessage> history, List<Content> current,
            long estimatedTokens, boolean callable, boolean degraded, long compressedThrough,String styleExamples,List<Long> styleFragmentIds) {
        public View { history = List.copyOf(history); current = List.copyOf(current);styleFragmentIds=List.copyOf(styleFragmentIds); }
    }

    public static class ConversationBusyException extends IllegalStateException {
        public ConversationBusyException() { super("该会话正在处理中，请重试相同批次"); }
    }
}
