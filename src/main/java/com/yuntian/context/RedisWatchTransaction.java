package com.yuntian.context;

import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/** 同一连接上的 WATCH、读取、MULTI 和 EXEC；不使用脚本，也不启用全局 Redis 事务。 */
final class RedisWatchTransaction {
    private static final int MAX_ATTEMPTS = 16;
    private final StringRedisTemplate redis;

    RedisWatchTransaction(StringRedisTemplate redis) {
        if (redis.getConnectionFactory() instanceof LettuceConnectionFactory factory
                && (factory.getShareNativeConnection() || factory.getClientConfiguration().getClientOptions()
                .map(options -> options.isAutoReconnect()).orElse(true))) {
            throw new IllegalArgumentException("上下文事务必须使用不共享、禁止自动重连的专用 Redis 连接");
        }
        this.redis = redis;
    }

    <T> T execute(List<String> keys, Function<RedisConnection, Plan<T>> prepare) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            // exposeConnection=true：异常时可以真正关闭连接，而不是被模板的 close 抑制代理拦截。
            Attempt<T> result = redis.execute((RedisCallback<Attempt<T>>) connection ->
                    attempt(connection, keys, prepare), true);
            if (result == null) throw new IllegalStateException("Redis 未返回事务结果");
            if (!result.conflicted()) return result.value();
        }
        throw new ContextSummaryStore.StateConflictException("WATCH_RETRIES_EXHAUSTED");
    }

    private <T> Attempt<T> attempt(RedisConnection connection, List<String> keys,
            Function<RedisConnection, Plan<T>> prepare) {
        if (connection.isQueueing() || connection.isPipelined()) {
            throw new IllegalStateException("上下文事务不能嵌套在 MULTI 或 pipeline 中");
        }
        requireExpiryWatchSupport(connection);
        boolean watching = false;
        boolean multi = false;
        boolean executing = false;
        try {
            watching = true;
            connection.watch(keys.stream().map(RedisWatchTransaction::bytes).toArray(byte[][]::new));
            Plan<T> plan = prepare.apply(connection);
            // 所有业务检查都在 WATCH 后、MULTI 前完成；事务内最多一条写命令，避免部分提交。
            multi = true;
            connection.multi();
            if (plan.write() != null) plan.write().accept(connection);
            // 读取也走 EXEC 验证快照。GET 保留一个非状态结果，避免驱动过滤 HMSET 的 OK 后出现空结果。
            connection.stringCommands().get(bytes(keys.get(0)));
            executing = true;
            List<Object> replies = connection.exec();
            executing = false;
            multi = false;
            watching = false;
            if (replies == null) return new Attempt<>(true, null);
            validateReplies(replies, plan.expectedLease());
            return new Attempt<>(false, plan.value());
        } catch (RuntimeException failure) {
            if (executing || failure instanceof ContextSummaryStore.CommitOutcomeUnknownException) {
                // EXEC 可能已落库，不能宣称失败或盲目重放；调用方需恢复连接后以原参数幂等核对。
                var unknown = failure instanceof ContextSummaryStore.CommitOutcomeUnknownException
                        ? failure : new ContextSummaryStore.CommitOutcomeUnknownException(failure);
                close(connection, unknown);
                throw unknown;
            }
            try {
                if (multi) connection.discard();
                else if (watching) connection.unwatch();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
                close(connection, failure);
            }
            throw failure;
        }
    }

    private static void validateReplies(List<Object> replies, String expectedLease) {
        byte[] expected = expectedLease == null ? null : bytes(expectedLease);
        Object last = replies.isEmpty() ? new Object() : replies.get(replies.size() - 1);
        if ((last != null && !(last instanceof byte[])) || !Arrays.equals(expected, asBytes(last))) {
            throw new ContextSummaryStore.CommitOutcomeUnknownException(
                    new IllegalStateException("EXEC 所有权校验结果异常"));
        }
        for (int i = 0; i < replies.size() - 1; i++) {
            Object reply = replies.get(i);
            // HMSET 的状态可能被 Spring/Lettuce 过滤；续租为 Boolean，释放锁为 Long。
            if (!(Boolean.TRUE.equals(reply) || Long.valueOf(1).equals(reply)
                    || "OK".equals(reply) || Arrays.equals(bytes("OK"), asBytes(reply)))) {
                throw new ContextSummaryStore.CommitOutcomeUnknownException(
                        new IllegalStateException("EXEC 写命令返回异常：" + reply));
            }
        }
    }

    private static byte[] asBytes(Object value) { return value instanceof byte[] bytes ? bytes : null; }

    private static void close(RedisConnection connection, RuntimeException failure) {
        try { connection.close(); }
        catch (RuntimeException closeFailure) { failure.addSuppressed(closeFailure); }
    }

    private static void requireExpiryWatchSupport(RedisConnection connection) {
        var info = connection.serverCommands().info("server");
        String version = info == null ? "" : info.getProperty("redis_version", "");
        String[] parts = version.split("[.-]");
        try {
            int major = Integer.parseInt(parts[0]);
            int minor = Integer.parseInt(parts[1]);
            int patch = Integer.parseInt(parts[2]);
            if (major > 6 || (major == 6 && (minor > 0 || patch >= 9))) return;
        } catch (RuntimeException ignored) { /* 未能确认过期 key 能中止 WATCH，按不支持处理。 */ }
        throw new IllegalStateException("上下文事务要求 Redis >= 6.0.9，保证租约过期使 WATCH 失效");
    }

    static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    record Plan<T>(T value, Consumer<RedisConnection> write, String expectedLease) {
        Plan { Objects.requireNonNull(value); }
    }
    private record Attempt<T>(boolean conflicted, T value) {}
}
