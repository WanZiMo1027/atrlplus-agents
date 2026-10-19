package com.yuntian.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.sql.*;
import java.time.Instant;
import java.util.*;
import static com.yuntian.style.StyleTypes.*;

/** 学习状态全部在独立 schema；短事务领取任务，远程模型调用期间不占事务或聊天租约。 */
public class StyleRepository {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final TransactionTemplate readTx;
    private final ObjectMapper json;
    private final StyleProperties properties;
    private final String s;
    public StyleRepository(DataSource source, ObjectMapper json, StyleProperties properties) {
        this.jdbc = new JdbcTemplate(source); this.tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.readTx = new TransactionTemplate(new DataSourceTransactionManager(source));
        this.readTx.setReadOnly(true);
        this.readTx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.json = json; this.properties = properties; this.s = properties.getSchema() + ".";
    }
    public <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); } catch (Exception e) { throw new IllegalStateException("STYLE_STORED_PAYLOAD_INVALID"); }
    }
    private String encode(Object value) {
        try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException("STYLE_SERIALIZATION_FAILED"); }
    }
    public record Checkpoint(long cursor, Instant pendingSince, boolean fresh, long backfillThrough) {}
    public Checkpoint checkpoint(String conversation) {
        boolean fresh = jdbc.update("INSERT INTO " + s + "learning_checkpoint(conversation_id,extractor_version) VALUES (?,?) ON CONFLICT DO NOTHING", conversation, properties.getExtractorVersion()) == 1;
        return jdbc.queryForObject("SELECT cursor,pending_since,backfill_through FROM " + s + "learning_checkpoint WHERE conversation_id=? AND extractor_version=?",
                (rs, n) -> new Checkpoint(rs.getLong(1), instant(rs.getTimestamp(2)), fresh, rs.getLong(3)), conversation, properties.getExtractorVersion());
    }
    public void initializeBackfill(String conversation, long through) {
        jdbc.update("UPDATE " + s + "learning_checkpoint SET backfill_through=? WHERE conversation_id=? AND extractor_version=? AND cursor=0 AND backfill_through=0",
                through, conversation, properties.getExtractorVersion());
    }
    public void pending(String conversation) {
        jdbc.update("UPDATE " + s + "learning_checkpoint SET pending_since=COALESCE(pending_since,now()) WHERE conversation_id=? AND extractor_version=?", conversation, properties.getExtractorVersion());
    }
    public Enqueued enqueue(String conversation, Window window) {
        return tx.execute(status -> {
            // 与提交进度使用同一行锁；并发调度不能基于旧游标插入悬空任务。
            Long cursor = jdbc.queryForObject("SELECT cursor FROM " + s + "learning_checkpoint WHERE conversation_id=? AND extractor_version=? FOR UPDATE", Long.class, conversation, properties.getExtractorVersion());
            if (cursor == null || cursor != window.fromSequence()) throw new IllegalStateException("STYLE_CURSOR_CHANGED");
            UUID id = UUID.randomUUID();
            jdbc.update("INSERT INTO " + s + "extraction_job(id,conversation_id,kind,status,from_sequence,through_sequence,extractor_version,payload,scanned,effective) "
                    + "VALUES (?,?,'extract','pending',?,?,?,?::jsonb,?,?) ON CONFLICT DO NOTHING",
                    id, conversation, window.fromSequence(), window.throughSequence(), properties.getExtractorVersion(),
                    encode(window), window.scanned(), window.effective());
            return jdbc.queryForObject("SELECT id,status FROM " + s + "extraction_job WHERE conversation_id=? AND kind='extract' AND from_sequence=? AND extractor_version=?",
                    (rs, n) -> new Enqueued(rs.getObject(1, UUID.class), rs.getString(2)), conversation, window.fromSequence(), properties.getExtractorVersion());
        });
    }
    public Optional<Enqueued> currentJob(String conversation, long cursor) {
        return jdbc.query("SELECT id,status FROM " + s + "extraction_job WHERE conversation_id=? AND kind='extract' AND from_sequence=? AND extractor_version=?",
                (rs, n) -> new Enqueued(rs.getObject(1, UUID.class), rs.getString(2)), conversation, cursor, properties.getExtractorVersion()).stream().findFirst();
    }
    public Optional<Job> claim(Collection<String> conversations, String version, String embeddingRequestProfile) {
        return tx.execute(status -> {
            // 超时任务不会永远卡住；第三次运行后失联同样进入可人工重试的失败状态。
            jdbc.update("UPDATE " + s + "extraction_job SET status='failed',error_code='STYLE_LEASE_EXPIRED',claim_token=NULL,lease_until=NULL "
                    + "WHERE status='running' AND lease_until<now() AND attempts>=3");
            String marks = String.join(",", Collections.nCopies(conversations.size(), "?"));
            List<Object> args = new ArrayList<>(conversations); args.add(version); args.add(version); args.add(embeddingRequestProfile);
            var ids = jdbc.query("SELECT id FROM " + s + "extraction_job WHERE conversation_id IN (" + marks + ") "
                    + "AND ((kind='extract' AND extractor_version=?) OR (kind='embed' AND extractor_version=? AND profile=?)) AND attempts<3 "
                    + "AND ((status IN ('pending','retry') AND next_attempt_at<=now()) OR (status='running' AND lease_until<now())) "
                    + "ORDER BY created_at,id FOR UPDATE SKIP LOCKED LIMIT 1", (rs, n) -> rs.getObject(1, UUID.class), args.toArray());
            if (ids.isEmpty()) return Optional.<Job>empty();
            UUID id = ids.get(0), token = UUID.randomUUID();
            jdbc.update("UPDATE " + s + "extraction_job SET status='running',attempts=attempts+1,claim_token=?,lease_until=now()+(? * interval '1 millisecond'),updated_at=now() WHERE id=?",
                    token, properties.getLeaseDuration().toMillis(), id);
            return Optional.of(jdbc.queryForObject("SELECT * FROM " + s + "extraction_job WHERE id=?", this::job, id));
        });
    }
    private Job job(ResultSet rs, int n) throws SQLException {
        long fragment = rs.getLong("fragment_id"); Long fragmentId = rs.wasNull() ? null : fragment;
        return new Job(rs.getObject("id", UUID.class), rs.getString("conversation_id"), rs.getString("kind"),
                rs.getString("status"), rs.getLong("from_sequence"), rs.getLong("through_sequence"), rs.getInt("attempts"),
                rs.getObject("claim_token", UUID.class), fragmentId, rs.getString("payload"), rs.getString("profile"), instant(rs.getTimestamp("lease_until")));
    }
    private void requireClaim(Job job) {
        var owned = jdbc.queryForList("SELECT id FROM " + s + "extraction_job WHERE id=? AND status='running' AND claim_token=? AND lease_until>now() FOR UPDATE",
                job.id(), job.claimToken());
        if (owned.isEmpty()) throw new IllegalStateException("STYLE_LEASE_LOST");
    }
    public void renew(Job job) {
        int updated = jdbc.update("UPDATE " + s + "extraction_job SET lease_until=now()+(? * interval '1 millisecond') "
                + "WHERE id=? AND status='running' AND claim_token=? AND lease_until>now()",
                properties.getLeaseDuration().toMillis(), job.id(), job.claimToken());
        if (updated != 1) throw new IllegalStateException("STYLE_LEASE_LOST");
    }
    public void completeExtraction(Job job, Extraction result, String embeddingRequestProfile) {
        if (!job.kind().equals("extract")) throw new IllegalArgumentException("STYLE_WRONG_JOB_KIND");
        tx.executeWithoutResult(status -> {
            // 与 enqueue 一致先锁游标再锁任务，避免重复触发与提交交错时形成反向锁序。
            Long cursor = jdbc.queryForObject("SELECT cursor FROM " + s + "learning_checkpoint WHERE conversation_id=? AND extractor_version=? FOR UPDATE", Long.class, job.conversationId(), properties.getExtractorVersion());
            requireClaim(job);
            if (cursor == null || cursor != job.fromSequence()) throw new IllegalStateException("STYLE_CURSOR_CHANGED");
            for (Fragment fragment : result.fragments()) {
                jdbc.update("INSERT INTO " + s + "interaction_fragment(conversation_id,fingerprint,extractor_version,through_sequence,payload) VALUES (?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                        job.conversationId(), fragment.fingerprint(), properties.getExtractorVersion(), job.throughSequence(), encode(fragment));
                Long id = jdbc.queryForObject("SELECT id FROM " + s + "interaction_fragment WHERE conversation_id=? AND fingerprint=? AND extractor_version=?", Long.class,
                        job.conversationId(), fragment.fingerprint(), properties.getExtractorVersion());
                enqueueEmbedding(job.conversationId(), id, fragment, embeddingRequestProfile);
            }
            jdbc.update("UPDATE " + s + "learning_checkpoint c SET cursor=?,scanned=c.scanned+j.scanned,effective=c.effective+j.effective,pending_since=NULL,updated_at=now() "
                    + "FROM " + s + "extraction_job j WHERE c.conversation_id=? AND c.extractor_version=j.extractor_version AND j.id=?", job.throughSequence(), job.conversationId(), job.id());
            jdbc.update("UPDATE " + s + "extraction_job SET status='done',claim_token=NULL,lease_until=NULL,error_code=NULL,rejected=?,input_tokens=?,output_tokens=?,updated_at=now() WHERE id=?",
                    result.rejected(), result.inputTokens(), result.outputTokens(), job.id());
        });
    }
    private void enqueueEmbedding(String conversation, Long id, Fragment fragment, String profile) {
        jdbc.update("INSERT INTO " + s + "extraction_job(id,conversation_id,kind,status,from_sequence,through_sequence,extractor_version,fragment_id,profile,payload) "
                + "VALUES (?,?,'embed','pending',0,0,?,?,?,?::jsonb) ON CONFLICT DO NOTHING", UUID.randomUUID(), conversation,
                properties.getExtractorVersion(), id, profile, encode(fragment));
    }
    public void ensureEmbeddingJobs(String conversation, String profile) {
        // 更换模型或模板后，保留旧向量并为新配置补齐独立任务。
        jdbc.query("SELECT id,payload FROM " + s + "interaction_fragment f WHERE conversation_id=? AND extractor_version=? AND NOT EXISTS (SELECT 1 FROM " + s
                        + "extraction_job j WHERE j.fragment_id=f.id AND j.kind='embed' AND j.profile=?) LIMIT 100",
                (RowCallbackHandler) rs -> enqueueEmbedding(conversation, rs.getLong(1), decode(rs.getString(2), Fragment.class), profile), conversation, properties.getExtractorVersion(), profile);
    }
    public void completeEmbedding(Job job, StyleEmbeddingClient.Vector vector) {
        if (!job.kind().equals("embed")) throw new IllegalArgumentException("STYLE_WRONG_JOB_KIND");
        tx.executeWithoutResult(status -> {
            requireClaim(job);
            // 锁定模型配置后确认维度，避免跨实例首次写入不同维度而无法恢复。
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                try (var statement = connection.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?,0))")) {
                    statement.setString(1, job.profile()); statement.execute();
                }
                return null;
            });
            verifyVectorProfile(job.profile(), vector);
            jdbc.update("INSERT INTO " + s + "fragment_embedding(fragment_id,profile,request_profile,dimensions,embedding) VALUES (?,?,?,?,?::public.vector) ON CONFLICT DO NOTHING",
                    job.fragmentId(), vector.profile(), job.profile(), vector.values().length, StyleEmbeddingClient.literal(vector.values()));
            jdbc.update("UPDATE " + s + "extraction_job SET status='done',claim_token=NULL,lease_until=NULL,error_code=NULL,input_tokens=?,updated_at=now() WHERE id=?", vector.inputTokens(), job.id());
        });
    }
    public void fail(Job job, String code) {
        // 错误只存固定代号，第三方异常正文可能包含请求凭据或群聊，不可写入任务或日志。
        String safe = code != null && code.matches("STYLE_[A-Z0-9_]+") ? code : "STYLE_TASK_FAILED";
        jdbc.update("UPDATE " + s + "extraction_job SET status=CASE WHEN attempts>=3 THEN 'failed' ELSE 'retry' END,"
                + "next_attempt_at=now()+(CASE WHEN attempts=1 THEN 60 ELSE 300 END * interval '1 second'),error_code=?,claim_token=NULL,lease_until=NULL,updated_at=now() "
                + "WHERE id=? AND claim_token=? AND status='running' AND lease_until>now()", safe, job.id(), job.claimToken());
    }
    public Enqueued retry(UUID id, Collection<String> conversations) {
        return tx.execute(status -> {
            var jobs = jdbc.query("SELECT * FROM " + s + "extraction_job WHERE id=? AND extractor_version=? FOR UPDATE", this::job, id, properties.getExtractorVersion());
            if (jobs.isEmpty() || !conversations.contains(jobs.get(0).conversationId())) throw new NoSuchElementException("STYLE_JOB_NOT_FOUND");
            if (!jobs.get(0).status().equals("failed")) throw new IllegalArgumentException("STYLE_JOB_NOT_FAILED");
            jdbc.update("UPDATE " + s + "extraction_job SET status='pending',attempts=0,error_code=NULL,next_attempt_at=now(),updated_at=now() WHERE id=?", id);
            return new Enqueued(id, "pending");
        });
    }
    public Status status(String conversation, String requestProfile) {
        var cp = checkpoint(conversation);
        var counts = jdbc.queryForMap("SELECT scanned,effective FROM " + s + "learning_checkpoint WHERE conversation_id=? AND extractor_version=?", conversation, properties.getExtractorVersion());
        long fragments = jdbc.queryForObject("SELECT count(*) FROM " + s + "interaction_fragment WHERE conversation_id=? AND extractor_version=?", Long.class, conversation, properties.getExtractorVersion());
        long ready = jdbc.queryForObject("SELECT count(*) FROM " + s + "extraction_job WHERE conversation_id=? AND extractor_version=? AND kind='embed' AND profile=? AND status='done'", Long.class, conversation, properties.getExtractorVersion(), requestProfile);
        long failed = jdbc.queryForObject("SELECT count(*) FROM " + s + "extraction_job WHERE conversation_id=? AND extractor_version=? AND kind='embed' AND profile=? AND status='failed'", Long.class, conversation, properties.getExtractorVersion(), requestProfile);
        var jobs = jdbc.queryForList("SELECT id,kind,status,from_sequence,through_sequence,attempts,error_code,scanned,effective,rejected,input_tokens,output_tokens,created_at,updated_at FROM "
                + s + "extraction_job WHERE conversation_id=? AND extractor_version=? ORDER BY created_at DESC,id LIMIT 30", conversation, properties.getExtractorVersion());
        return new Status(cp.cursor(), ((Number) counts.get("scanned")).longValue(), ((Number) counts.get("effective")).longValue(), fragments, ready, failed, jobs);
    }
    public FragmentPage fragments(String conversation, long after, int limit, String requestProfile) {
        return fragments(conversation, after, limit, requestProfile, properties.getExtractorVersion(), "", null, null);
    }
    public Catalog catalog() {
        // 目录仅查询片段，不借用会创建 checkpoint 的 status 方法，也不扫描 Redis。
        Map<String, LinkedHashMap<String, Long>> versions = new HashMap<>();
        List<String> conversations = properties.getConversations().stream().distinct().toList();
        if (!conversations.isEmpty()) {
            String marks = String.join(",", Collections.nCopies(conversations.size(), "?"));
            jdbc.query("SELECT conversation_id,extractor_version,count(*) AS fragments FROM " + s
                            + "interaction_fragment WHERE conversation_id IN (" + marks + ") GROUP BY conversation_id,extractor_version ORDER BY max(created_at) DESC,extractor_version",
                    (RowCallbackHandler) rs -> versions.computeIfAbsent(rs.getString(1), ignored -> new LinkedHashMap<>()).put(rs.getString(2), rs.getLong(3)), conversations.toArray());
        }
        var entries = conversations.stream().map(id -> {
            Map<String, Long> found = versions.getOrDefault(id, new LinkedHashMap<>());
            List<VersionOption> options = new ArrayList<>();
            options.add(new VersionOption(properties.getExtractorVersion(), found.getOrDefault(properties.getExtractorVersion(), 0L), true));
            found.forEach((version, count) -> { if (!version.equals(properties.getExtractorVersion())) options.add(new VersionOption(version, count, false)); });
            String[] parts = id.split(":");
            return new ConversationCatalog(id, parts[3], parts[1], List.copyOf(options));
        }).toList();
        return new Catalog(properties.getExtractorVersion(), entries, INTENTS.stream().sorted().toList(), SERIOUSNESS.stream().sorted().toList());
    }
    public FragmentPage fragments(String conversation, long after, int limit, String requestProfile,
                                  String version, String query, String intent, String seriousness) {
        return readTx.execute(transaction -> {
            // 总数与分页来自同一只读快照；所有筛选在数据库完成，不能只过滤已加载的一页。
            String where = " WHERE f.conversation_id=? AND f.extractor_version=?";
            List<Object> filters = new ArrayList<>(List.of(conversation, version));
            if (intent != null) { where += " AND f.payload->>'intent'=?"; filters.add(intent); }
            if (seriousness != null) { where += " AND f.payload->>'seriousness'=?"; filters.add(seriousness); }
            if (!query.isEmpty()) {
                // strpos 实现字面关键词，百分号、下划线不会成为 LIKE 通配符。
                where += " AND (strpos(lower(concat_ws(' ',f.payload->>'scene',f.payload->>'interaction',f.payload->>'emotion')),lower(?))>0"
                        + " OR EXISTS (SELECT 1 FROM jsonb_array_elements(COALESCE(f.payload->'context','[]'::jsonb)||COALESCE(f.payload->'replies','[]'::jsonb)) m"
                        + " WHERE strpos(lower(concat_ws(' ',m->>'senderName',m->>'senderId',m->>'eventId',m->>'text',m->>'background')),lower(?))>0))";
                filters.add(query); filters.add(query);
            }
            long total = jdbc.queryForObject("SELECT count(*) FROM " + s + "interaction_fragment f" + where, Long.class, filters.toArray());
            List<Object> args = new ArrayList<>(); args.add(requestProfile); args.addAll(filters); args.add(after); args.add(limit + 1);
            var rows = jdbc.query("SELECT f.id,f.conversation_id,f.through_sequence,f.payload,COALESCE(j.status,'not_generated') AS embedding_status FROM "
                            + s + "interaction_fragment f LEFT JOIN " + s + "extraction_job j ON j.fragment_id=f.id AND j.kind='embed' AND j.profile=? "
                            + where + " AND f.id>? ORDER BY f.id LIMIT ?",
                    (rs, n) -> new FragmentView(rs.getLong(1), rs.getString(2), rs.getLong(3), decode(rs.getString(4), Fragment.class), rs.getString(5)), args.toArray());
            boolean more = rows.size() > limit; var page = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
            return new FragmentPage(page, more ? page.get(page.size() - 1).id() : null, version, total);
        });
    }
    public List<SearchHit> search(SearchRequest request, StyleEmbeddingClient.Vector vector) {
        return search(request, vector, jdbc);
    }
    public List<SearchHit> search(SearchRequest request, StyleEmbeddingClient.Vector vector, StyleReplyDeadline deadline) {
        return search(request, vector, timed(deadline));
    }
    private JdbcTemplate timed(StyleReplyDeadline deadline) {
        // 独立模板只限制本次只读语句，不能修改后台学习或管理查询的全局超时。
        var limited=new JdbcTemplate(Objects.requireNonNull(jdbc.getDataSource()));limited.setQueryTimeout(deadline.sqlSeconds());return limited;
    }
    public boolean hasReadyVectors(String conversation, String requestProfile, StyleReplyDeadline deadline) {
        return Boolean.TRUE.equals(timed(deadline).queryForObject("SELECT EXISTS(SELECT 1 FROM "+s+"fragment_embedding e JOIN "+s
                +"interaction_fragment f ON f.id=e.fragment_id WHERE f.conversation_id=? AND f.extractor_version=? AND e.request_profile=?)",
                Boolean.class,conversation,properties.getExtractorVersion(),requestProfile));
    }
    public void verifyVectorProfile(String requestProfile, StyleEmbeddingClient.Vector vector, StyleReplyDeadline deadline) {
        Long mismatches=timed(deadline).queryForObject("SELECT count(*) FROM "+s+"fragment_embedding WHERE request_profile=? AND dimensions<>?",Long.class,requestProfile,vector.values().length);
        if(mismatches!=null&&mismatches>0) throw new IllegalStateException("STYLE_EMBEDDING_DIMENSION_CHANGED");
    }
    private List<SearchHit> search(SearchRequest request, StyleEmbeddingClient.Vector vector, JdbcTemplate query) {
        String sql = "SELECT f.id,f.payload,1-(e.embedding OPERATOR(public.<=>) ?::public.vector) AS similarity FROM " + s + "fragment_embedding e JOIN " + s
                + "interaction_fragment f ON f.id=e.fragment_id WHERE f.conversation_id=? AND f.extractor_version=? AND e.profile=? AND e.dimensions=?";
        List<Object> args = new ArrayList<>(List.of(StyleEmbeddingClient.literal(vector.values()), request.conversationId(), properties.getExtractorVersion(), vector.profile(), vector.values().length));
        if (request.intent() != null) { sql += " AND f.payload->>'intent'=?"; args.add(request.intent()); }
        if (request.seriousness() != null) { sql += " AND f.payload->>'seriousness'=?"; args.add(request.seriousness()); }
        sql += " ORDER BY similarity DESC,f.id LIMIT ?"; args.add(request.limit() == null ? 5 : request.limit());
        return query.query(sql, (rs, n) -> new SearchHit(rs.getLong(1), decode(rs.getString(2), Fragment.class), rs.getDouble(3)), args.toArray());
    }
    public void verifyVectorProfile(String requestProfile, StyleEmbeddingClient.Vector vector) {
        Long mismatches = jdbc.queryForObject("SELECT count(*) FROM " + s + "fragment_embedding WHERE request_profile=? AND dimensions<>?", Long.class,
                requestProfile, vector.values().length);
        if (mismatches != null && mismatches > 0) throw new IllegalStateException("STYLE_EMBEDDING_DIMENSION_CHANGED");
    }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
}
