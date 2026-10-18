package com.yuntian.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntian.dto.AgentChatResponse;
import com.yuntian.dto.ChatMessageItem;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.*;

/**
 * 上下文状态的唯一 Redis 写入入口。游标以完整 QQ 批次递增，摘要提交只退出活跃视图。
 * 不删除原文；归档确认与清理将在后续阶段独立接入。
 */
@Component
public class ContextSummaryStore {
    private static final String PREFIX = "qq:context:v1:";
    static final int MAX_SUMMARY_BYTES = 64 * 1024;
    private static final TypeReference<List<ChatMessageItem>> MESSAGES_TYPE = new TypeReference<>() {};
    private static final long MAX_INTEGER = 9007199254740991L;

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final String prefix;
    private final int maxRetainedBatches;
    private final long maxRetainedBytes;
    private final int maxBatchBytes;
    private final RedisWatchTransaction transactions;

    @Autowired
    public ContextSummaryStore(ContextRedisConnection connection, ObjectMapper json,
            @Value("${agent.context.storage.max-retained-batches:1000}") int maxRetainedBatches,
            @Value("${agent.context.storage.max-retained-bytes:16777216}") long maxRetainedBytes,
            @Value("${agent.context.storage.max-batch-bytes:262144}") int maxBatchBytes) {
        this(connection.template(), json, PREFIX, maxRetainedBatches, maxRetainedBytes, maxBatchBytes);
    }

    /** 测试通过独立命名空间访问真实 Redis，不复用或清理应用会话。 */
    ContextSummaryStore(StringRedisTemplate redis, ObjectMapper json, String prefix,
            int maxRetainedBatches, long maxRetainedBytes, int maxBatchBytes) {
        if (maxRetainedBatches <= 0 || maxBatchBytes <= 0 || maxRetainedBytes < maxBatchBytes
                || maxRetainedBytes >= 9007199254740991L) {
            throw new IllegalArgumentException("上下文存储容量必须为正，批次上限不能超过会话上限");
        }
        this.redis = Objects.requireNonNull(redis);
        this.json = Objects.requireNonNull(json);
        this.prefix = requireText(prefix, "prefix");
        this.maxRetainedBatches = maxRetainedBatches;
        this.maxRetainedBytes = maxRetainedBytes;
        this.maxBatchBytes = maxBatchBytes;
        this.transactions = new RedisWatchTransaction(redis);
    }

    /** 租约 token 每次重新生成，避免旧任务与新任务复用标识造成 ABA 问题。 */
    public Optional<Lease> tryAcquire(String conversationId, Duration duration) {
        long millis = leaseMillis(duration);
        Lease lease = new Lease(requireText(conversationId, "conversationId"), UUID.randomUUID().toString());
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(leaseKey(conversationId), lease.token(),
                Duration.ofMillis(millis))) ? Optional.of(lease) : Optional.empty();
    }

    public boolean renew(Lease lease, Duration duration) {
        long millis = leaseMillis(duration);
        return changeLease(lease, connection -> connection.keyCommands().pExpire(
                RedisWatchTransaction.bytes(leaseKey(lease.conversationId())), millis), false);
    }

    public boolean release(Lease lease) {
        return changeLease(lease, connection -> connection.keyCommands().del(
                RedisWatchTransaction.bytes(leaseKey(lease.conversationId()))), true);
    }

    private boolean changeLease(Lease lease, java.util.function.Consumer<RedisConnection> write, boolean release) {
        String key = leaseKey(lease.conversationId());
        return transactions.execute(List.of(key), connection -> {
            String token = text(connection.stringCommands().get(RedisWatchTransaction.bytes(key)));
            boolean owned = lease.token().equals(token)
                    && connection.keyCommands().pTtl(RedisWatchTransaction.bytes(key)) > 0;
            // 不持有锁时也以只读 EXEC 检查观察结果；旧 finally 绝不会删除新持有者的锁。
            return new RedisWatchTransaction.Plan<>(owned, owned ? write : null,
                    owned && release ? null : token);
        });
    }

    /** 此方法只适合调用前检查；真正的提交由 WATCH 保护租约，不能先 owns 再单独写入。 */
    public boolean owns(Lease lease) {
        return lease.token().equals(redis.opsForValue().get(leaseKey(lease.conversationId())));
    }

    public Snapshot read(Lease lease) {
        return withState(lease, state -> new Change<>(state.snapshot(lease.conversationId()), Map.of()));
    }

    /** 管理页面只读快照：不获取租约、不迁移、不续期；单次 HGETALL 与主流程的单命令提交对应。 */
    public Optional<Snapshot> readForAdmin(String conversationId) {
        List<String> keys = keys(conversationId);
        Map<String, String> fields = redis.execute((RedisCallback<Map<String, String>>) c -> hash(c, keys.get(1)));
        if (fields == null || fields.isEmpty()) return Optional.empty();
        if ("2".equals(fields.get("schemaVersion"))) {
            return Optional.of(new State(fields, false, conversationId).snapshot(conversationId));
        }
        if (fields.containsKey("schemaVersion")) throw corrupt();
        // 兼容旧的多 key 布局：重新在同一连接的只读 EXEC 中取齐快照，避免跨命令观察到迁移中间状态。
        return redis.execute((RedisCallback<Optional<Snapshot>>) c -> {
            try {
                c.multi();
                for (int i = 1; i <= 5; i++) c.hashCommands().hGetAll(RedisWatchTransaction.bytes(keys.get(i)));
                c.zSetCommands().zRangeWithScores(RedisWatchTransaction.bytes(keys.get(6)), 0, -1);
                List<Object> replies = c.exec();
                if (replies == null || replies.size() != 6 || replies.stream().anyMatch(Throwable.class::isInstance)) throw corrupt();
                Map<String, String> snapshot = decodeHash(replies.get(0));
                if (snapshot.isEmpty()) return Optional.empty();
                boolean legacy = !snapshot.containsKey("schemaVersion");
                if (legacy) {
                    for (int i = 1; i <= 4; i++) {
                        String fieldPrefix = List.of("input:", "request:", "event:", "decision:").get(i - 1);
                        decodeHash(replies.get(i)).forEach((field, value) -> snapshot.put(fieldPrefix + field, value));
                    }
                    @SuppressWarnings("unchecked")
                    var order = (Set<org.springframework.data.redis.connection.zset.Tuple>) replies.get(5);
                    validateLegacyOrder(snapshot, order);
                } else if (!"2".equals(snapshot.get("schemaVersion"))) throw corrupt();
                return Optional.of(new State(snapshot, legacy, conversationId).snapshot(conversationId));
            } finally {
                // 专用连接禁止自动重连；异常后的排队状态不得泄漏给后续查询。
                if (c.isQueueing()) c.discard();
            }
        });
    }

    /** SCAN 的 cursor 为 Redis 无符号十进制游标，不接受客户端提供的任意 key。 */
    public ConversationScan scanForAdmin(String cursor) {
        if (cursor == null || !cursor.matches("0|[1-9][0-9]{0,19}")
                || new java.math.BigInteger(cursor).bitLength() > 64) throw new IllegalArgumentException("扫描游标无效");
        return redis.execute((RedisCallback<ConversationScan>) c -> {
            // Spring 的 Cursor 不接受外部起始游标；仅 SCAN 使用当前 Spring 连接的 Lettuce 游标 API。
            @SuppressWarnings("unchecked")
            var commands = (io.lettuce.core.api.async.RedisAsyncCommands<byte[], byte[]>) c.getNativeConnection();
            var page = io.lettuce.core.LettuceFutures.awaitOrCancel(commands.scan(io.lettuce.core.ScanCursor.of(cursor),
                    io.lettuce.core.ScanArgs.Builder.matches(prefix + "*:state").limit(100)), 10, java.util.concurrent.TimeUnit.SECONDS);
            Set<String> ids = new TreeSet<>();
            for (byte[] rawKey : page.getKeys()) {
                String key = text(rawKey);
                String id = text(c.hashCommands().hGet(RedisWatchTransaction.bytes(key), RedisWatchTransaction.bytes("conversationId")));
                if (id == null) continue; // SCAN 后被外部删除的条目可以跳过，故障不能伪装为空列表。
                if (!keys(id).get(1).equals(key)) throw corrupt();
                ids.add(id);
            }
            return new ConversationScan(List.copyOf(ids), page.getCursor());
        }, true);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> decodeHash(Object raw) {
        if (!(raw instanceof Map<?, ?>)) throw corrupt();
        Map<String, String> decoded = new HashMap<>();
        ((Map<byte[], byte[]>) raw).forEach((field, value) -> decoded.put(text(field), text(value)));
        return decoded;
    }

    /** 先持久化接收的群友原文；请求正文与有序事件共同决定批次幂等标识。 */
    public BeginResult beginBatch(Lease lease, long expectedRevision, List<ChatMessageItem> messages) {
        nonNegative(expectedRevision);
        List<ChatMessageItem> copy = List.copyOf(messages);
        if (copy.isEmpty()) throw new IllegalArgumentException("上下文批次不能为空");
        Set<String> events = new HashSet<>();
        for (ChatMessageItem item : copy) {
            if (!events.add(requireText(item.eventId(), "eventId"))) {
                throw new IllegalArgumentException("同一批次不能包含重复 eventId");
            }
        }
        String payload = encode(copy);
        String fingerprint = digest(payload);
        return withState(lease, state -> {
            String existing = state.fields.get("request:" + fingerprint);
            if (existing != null) {
                long sequence = Long.parseLong(existing);
                return new Change<>(new BeginResult(sequence, state.revision, true,
                        decision(state.fields.get("decision:" + sequence))), Map.of());
            }
            state.expectRevision(expectedRevision);
            if (copy.stream().anyMatch(item -> state.fields.containsKey("event:" + digest(item.eventId())))) {
                throw new StateConflictException("EVENT_CONFLICT");
            }
            long bytes = utf8Size(payload);
            if (state.batches.size() >= maxRetainedBatches || bytes > maxBatchBytes
                    || state.rawBytes + bytes > maxRetainedBytes) throw new CapacityExceededException();
            long sequence = increment(state.nextSequence);
            long revision = increment(state.revision);
            Map<String, String> updates = new HashMap<>(Map.of(
                    "conversationId", lease.conversationId(), "nextSequence", Long.toString(sequence),
                    "revision", Long.toString(revision), "rawBytes", Long.toString(state.rawBytes + bytes),
                    "input:" + sequence, payload, "request:" + fingerprint, Long.toString(sequence)));
            copy.forEach(item -> updates.put("event:" + digest(item.eventId()), Long.toString(sequence)));
            return new Change<>(new BeginResult(sequence, revision, false, null), updates);
        });
    }

    /** 只接受业务层过滤后的结果；沉默决策强制去除未发送的文字、引用及表情。 */
    public long completeBatch(Lease lease, long expectedRevision, long sequence, AgentChatResponse response) {
        nonNegative(expectedRevision);
        if (sequence <= 0) throw new IllegalArgumentException("批次序号必须为正");
        AgentChatResponse normalized = normalize(response);
        String payload = encode(normalized);
        return withState(lease, state -> {
            if (!state.fields.containsKey("input:" + sequence)) throw new StateConflictException("BATCH_NOT_FOUND");
            String existing = state.fields.get("decision:" + sequence);
            if (existing != null) {
                if (!existing.equals(payload)) throw new StateConflictException("DECISION_CONFLICT");
                return new Change<>(state.revision, Map.of());
            }
            state.expectRevision(expectedRevision);
            long bytes = state.rawBytes + utf8Size(payload);
            if (bytes > maxRetainedBytes) throw new CapacityExceededException();
            long revision = increment(state.revision);
            return new Change<>(revision, Map.of("decision:" + sequence, payload,
                    "revision", Long.toString(revision), "rawBytes", Long.toString(bytes)));
        });
    }

    /** 摘要、版本和游标由一条 Hash 写命令原子提交；成功之后原始记录仍然存在。 */
    public long commitSummary(Lease lease, long expectedRevision, long expectedVersion, long previousCursor,
            long throughSequence, String summary) {
        nonNegative(expectedRevision, expectedVersion, previousCursor, throughSequence);
        if (throughSequence <= previousCursor) throw new IllegalArgumentException("摘要游标必须前进");
        requireText(summary, "summary");
        return withState(lease, state -> {
            if (state.summaryVersion == expectedVersion + 1 && state.cursor == throughSequence
                    && state.summary.equals(summary)) return new Change<>(state.revision, Map.of());
            state.expectRevision(expectedRevision);
            if (state.summaryVersion != expectedVersion || state.cursor != previousCursor) {
                throw new StateConflictException("SUMMARY_VERSION_CONFLICT");
            }
            if (throughSequence > state.nextSequence) throw new StateConflictException("INVALID_CURSOR");
            if (utf8Size(summary) > MAX_SUMMARY_BYTES) throw new CapacityExceededException();
            if (state.batches.stream().anyMatch(batch -> batch.sequence() > state.cursor
                    && batch.sequence() <= throughSequence && !batch.completed())) {
                throw new StateConflictException("INCOMPLETE_BATCH");
            }
            long revision = increment(state.revision);
            return new Change<>(revision, Map.of("summary", summary,
                    "lastCompressedSequence", Long.toString(throughSequence),
                    "summaryVersion", Long.toString(increment(state.summaryVersion)),
                    "revision", Long.toString(revision)));
        });
    }

    private AgentChatResponse normalize(AgentChatResponse response) {
        Objects.requireNonNull(response, "规范化决策不能为空");
        if (!response.shouldReply()) return new AgentChatResponse(false, List.of(), null, null);
        List<String> replies = List.copyOf(Objects.requireNonNull(response.replies()));
        if (replies.size() > 3 || replies.stream().anyMatch(text -> text.isBlank() || !text.equals(text.strip()))
                || (replies.isEmpty() && (response.meme() == null || response.meme().isBlank()))
                || (replies.isEmpty() && response.replyToEventId() != null)) {
            throw new IllegalArgumentException("必须提交 AgentService 过滤后的决策");
        }
        return new AgentChatResponse(true, replies, response.replyToEventId(), response.meme());
    }

    private <T> T withState(Lease lease, java.util.function.Function<State, Change<T>> operation) {
        Objects.requireNonNull(lease);
        List<String> keys = keys(lease.conversationId());
        return transactions.execute(keys, connection -> {
            byte[] lock = RedisWatchTransaction.bytes(keys.get(0));
            if (!lease.token().equals(text(connection.stringCommands().get(lock)))
                    || connection.keyCommands().pTtl(lock) <= 0) throw new LeaseLostException();
            State state = loadState(connection, keys, lease.conversationId());
            Change<T> change = operation.apply(state);
            if (change.updates().isEmpty()) return new RedisWatchTransaction.Plan<>(change.value(), null, lease.token());
            Map<String, String> updates = new HashMap<>();
            // 旧布局首次修改时整体搬入 state；格式标记和所有数据同一命令提交，旧副本保留。
            if (state.legacy) {
                updates.putAll(Map.of("schemaVersion", "2", "conversationId", lease.conversationId(),
                        "nextSequence", "0", "revision", "0", "rawBytes", "0",
                        "summaryVersion", "0", "lastCompressedSequence", "0", "summary", ""));
                updates.putAll(state.fields);
            }
            updates.putAll(change.updates());
            Map<byte[], byte[]> encoded = new LinkedHashMap<>();
            updates.forEach((field, value) -> encoded.put(RedisWatchTransaction.bytes(field), RedisWatchTransaction.bytes(value)));
            return new RedisWatchTransaction.Plan<>(change.value(),
                    c -> c.hashCommands().hMSet(RedisWatchTransaction.bytes(keys.get(1)), encoded), lease.token());
        });
    }

    private State loadState(RedisConnection connection, List<String> keys, String conversationId) {
        type(connection, keys.get(1), DataType.HASH);
        Map<String, String> fields = hash(connection, keys.get(1));
        boolean legacy = !fields.containsKey("schemaVersion");
        if (legacy) {
            // 迁移前 WATCH 包含旧布局全部 key；任何并发写入都会令本次 EXEC 放弃。
            for (int i = 2; i < keys.size(); i++) type(connection, keys.get(i), i == 6 ? DataType.ZSET : DataType.HASH);
            for (int i = 2; i < 6; i++) {
                String fieldPrefix = List.of("input:", "request:", "event:", "decision:").get(i - 2);
                hash(connection, keys.get(i)).forEach((field, value) -> fields.put(fieldPrefix + field, value));
            }
            var order = connection.zSetCommands().zRangeWithScores(RedisWatchTransaction.bytes(keys.get(6)), 0, -1);
            if (order == null) throw corrupt();
            validateLegacyOrder(fields, order);
        } else if (!"2".equals(fields.get("schemaVersion"))) throw corrupt();
        return new State(fields, legacy, conversationId);
    }

    private static void validateLegacyOrder(Map<String, String> fields,
            Set<? extends org.springframework.data.redis.connection.zset.Tuple> order) {
        if (order == null) throw corrupt();
        Set<String> ordered = new HashSet<>();
        for (var tuple : order) {
            String sequence = text(tuple.getValue());
            if (tuple.getScore() == null || tuple.getScore() != counter(sequence)
                    || !ordered.add(sequence)) throw corrupt();
        }
        Set<String> inputs = new HashSet<>();
        fields.keySet().stream().filter(field -> field.startsWith("input:"))
                .forEach(field -> inputs.add(field.substring(6)));
        if (!ordered.equals(inputs)) throw corrupt();
    }

    private static Map<String, String> hash(RedisConnection connection, String key) {
        Map<byte[], byte[]> raw = connection.hashCommands().hGetAll(RedisWatchTransaction.bytes(key));
        if (raw == null) throw corrupt();
        Map<String, String> decoded = new HashMap<>();
        raw.forEach((field, value) -> decoded.put(text(field), text(value)));
        return decoded;
    }

    private static void type(RedisConnection connection, String key, DataType expected) {
        DataType actual = connection.keyCommands().type(RedisWatchTransaction.bytes(key));
        if (actual != DataType.NONE && actual != expected) throw corrupt();
    }

    /** 校验存储完整性，再进行业务判断；计数或索引损坏时禁止从零开始覆盖原文。 */
    private final class State {
        final Map<String, String> fields;
        final boolean legacy;
        final long nextSequence, revision, summaryVersion, cursor, rawBytes;
        final String summary;
        final List<Batch> batches = new ArrayList<>();

        State(Map<String, String> fields, boolean legacy, String conversationId) {
            this.fields = fields;
            this.legacy = legacy;
            nextSequence = counter(fields.getOrDefault("nextSequence", "0"));
            revision = counter(fields.getOrDefault("revision", "0"));
            summaryVersion = counter(fields.getOrDefault("summaryVersion", "0"));
            cursor = counter(fields.getOrDefault("lastCompressedSequence", "0"));
            rawBytes = counter(fields.getOrDefault("rawBytes", "0"));
            summary = fields.getOrDefault("summary", "");
            if (fields.isEmpty()) return;
            for (String required : List.of("conversationId", "nextSequence", "revision", "rawBytes")) {
                if (!fields.containsKey(required)) throw corrupt();
            }
            if (!legacy && !fields.keySet().containsAll(List.of("summaryVersion", "lastCompressedSequence", "summary"))) {
                throw corrupt();
            }
            if (!conversationId.equals(fields.get("conversationId")) || cursor > nextSequence
                    || (summaryVersion == 0 ? cursor != 0 || !summary.isEmpty() : cursor == 0 || summary.isBlank())
                    || utf8Size(summary) > MAX_SUMMARY_BYTES) throw corrupt();
            Map<String, String> expectedIndexes = new HashMap<>();
            SortedMap<Long, String> inputs = new TreeMap<>();
            long actualBytes = 0;
            long decisions = 0;
            for (var entry : fields.entrySet()) {
                if (entry.getKey().startsWith("input:")) {
                    String seq = entry.getKey().substring(6);
                    long sequence = counter(seq);
                    if (sequence == 0 || !Long.toString(sequence).equals(seq)) throw corrupt();
                    inputs.put(sequence, entry.getValue());
                }
            }
            if (inputs.size() != nextSequence) throw corrupt();
            long expectedSequence = 1;
            for (var entry : inputs.entrySet()) {
                if (entry.getKey() != expectedSequence++) throw corrupt();
                String seq = Long.toString(entry.getKey());
                List<ChatMessageItem> messages = parse(entry.getValue(), MESSAGES_TYPE);
                if (messages.isEmpty()) throw corrupt();
                expectedIndexes.put("request:" + digest(entry.getValue()), seq);
                for (ChatMessageItem message : messages) {
                    if (message.eventId() == null || message.eventId().isBlank()
                            || expectedIndexes.put("event:" + digest(message.eventId()), seq) != null) throw corrupt();
                }
                String result = fields.get("decision:" + seq);
                AgentChatResponse decision = decision(result);
                if (result != null) {
                    if (decision == null || !normalize(decision).equals(decision)) throw corrupt();
                    decisions++;
                    actualBytes += utf8Size(result);
                }
                actualBytes += utf8Size(entry.getValue());
                batches.add(new Batch(entry.getKey(), messages, decision));
            }
            if (actualBytes != rawBytes || revision != nextSequence + decisions + summaryVersion) throw corrupt();
            for (var entry : expectedIndexes.entrySet()) {
                if (!entry.getValue().equals(fields.get(entry.getKey()))) throw corrupt();
            }
            for (String field : fields.keySet()) {
                if ((field.startsWith("request:") || field.startsWith("event:")) && !expectedIndexes.containsKey(field)) throw corrupt();
                if (field.startsWith("decision:") && !fields.containsKey("input:" + field.substring(9))) throw corrupt();
            }
            if (batches.stream().anyMatch(batch -> batch.sequence() <= cursor && !batch.completed())) throw corrupt();
        }

        void expectRevision(long expected) {
            if (revision != expected) throw new StateConflictException("REVISION_CONFLICT");
        }

        Snapshot snapshot(String conversationId) {
            return new Snapshot(conversationId, nextSequence, revision, summaryVersion, cursor, summary, rawBytes, batches);
        }
    }

    private record Change<T>(T value, Map<String, String> updates) {}

    /** 对会话 ID 取摘要构造 hash tag，避免 ID 自带花括号改变 Redis Cluster 的分槽。 */
    private List<String> keys(String conversationId) {
        String base = prefix + "{" + digest(requireText(conversationId, "conversationId")) + "}:";
        return List.of(base + "lease", base + "state", base + "inputs", base + "requests",
                base + "events", base + "decisions", base + "order");
    }

    String leaseKey(String conversationId) { return keys(conversationId).get(0); }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("上下文序列化失败", e); }
    }

    private <T> T parse(String value, TypeReference<T> type) {
        try { return json.readValue(value, type); }
        catch (JsonProcessingException e) { throw new IllegalStateException("上下文记录无法恢复", e); }
    }

    private AgentChatResponse decision(String value) {
        if (value == null || value.isEmpty()) return null;
        try { return json.readValue(value, AgentChatResponse.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("规范化决策无法恢复", e); }
    }

    private static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static String text(byte[] value) {
        return value == null ? null : new String(value, StandardCharsets.UTF_8);
    }
    private static long utf8Size(String value) { return RedisWatchTransaction.bytes(value).length; }
    private static long increment(long value) { nonNegative(value + 1); return value + 1; }
    private static long counter(String value) {
        try {
            if (value == null || !value.matches("[0-9]+")) throw corrupt();
            long number = Long.parseLong(value);
            if (number < 0 || number >= MAX_INTEGER) throw corrupt();
            return number;
        } catch (NumberFormatException failure) { throw corrupt(); }
    }
    private static IllegalStateException corrupt() {
        return new IllegalStateException("Redis 上下文状态损坏，已停止写入");
    }
    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " 不能为空");
        return value;
    }

    private static void nonNegative(long... values) {
        for (long value : values) if (value < 0 || value >= 9007199254740991L) {
            throw new IllegalArgumentException("状态版本或游标超出精确整数范围");
        }
    }

    private static long leaseMillis(Duration duration) {
        long millis = Objects.requireNonNull(duration).toMillis();
        if (millis <= 0) throw new IllegalArgumentException("租约至少需要 1 毫秒");
        return millis;
    }

    public record Lease(String conversationId, String token) {
        public Lease { requireText(conversationId, "conversationId"); requireText(token, "token"); }
    }
    public record ConversationScan(List<String> conversationIds, String nextCursor) {}
    public record BeginResult(long sequence, long revision, boolean replayed, AgentChatResponse decision) {}
    public record Batch(long sequence, List<ChatMessageItem> messages, AgentChatResponse decision) {
        public Batch { messages = List.copyOf(messages); }
        public boolean completed() { return decision != null; }
    }
    public record Snapshot(String conversationId, long nextSequence, long revision, long summaryVersion,
            long lastCompressedSequence, String summary, long rawBytes, List<Batch> batches) {
        public Snapshot { batches = List.copyOf(batches); }
        public List<Batch> activeBatches() {
            return batches.stream().filter(batch -> batch.sequence() > lastCompressedSequence).toList();
        }
    }
    public static class LeaseLostException extends IllegalStateException {
        public LeaseLostException() { super("会话租约已失效，旧任务禁止提交状态"); }
    }
    public static class StateConflictException extends IllegalStateException {
        public StateConflictException(String reason) { super("上下文状态冲突：" + reason); }
    }
    public static class CapacityExceededException extends IllegalStateException {
        public CapacityExceededException() { super("上下文保留容量已满，需先可靠归档，不能淘汰唯一原文"); }
    }
    public static class CommitOutcomeUnknownException extends IllegalStateException {
        public CommitOutcomeUnknownException(Throwable cause) {
            super("Redis EXEC 结果不确定，恢复连接及有效租约后必须以原参数幂等核对，不能视为未提交", cause);
        }
    }
}
