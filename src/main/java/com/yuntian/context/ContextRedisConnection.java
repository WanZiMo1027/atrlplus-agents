package com.yuntian.context;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.ReadFrom;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** 上下文事务专用连接；普通 ChatMemory 的 Redis 工厂和模板继续由 Spring Boot 管理。 */
@Component
public final class ContextRedisConnection implements DisposableBean {
    private final LettuceConnectionFactory factory;
    private final StringRedisTemplate template;

    public ContextRedisConnection(LettuceConnectionFactory source) {
        if (source.isClusterAware()) {
            throw new IllegalArgumentException("第一阶段上下文 WATCH 事务仅支持单节点或 Sentinel 主节点");
        }
        var original = source.getClientConfiguration();
        var options = original.getClientOptions().orElseGet(ClientOptions::create).mutate()
                .autoReconnect(false)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS).build();
        var builder = LettuceClientConfiguration.builder().clientOptions(options).readFrom(ReadFrom.MASTER)
                .commandTimeout(original.getCommandTimeout())
                .shutdownTimeout(original.getShutdownTimeout()).shutdownQuietPeriod(original.getShutdownQuietPeriod());
        original.getClientResources().ifPresent(builder::clientResources);
        original.getClientName().ifPresent(builder::clientName);
        original.getRedisCredentialsProviderFactory().ifPresent(builder::redisCredentialsProviderFactory);
        if (original.isUseSsl()) {
            var ssl = builder.useSsl().verifyPeer(original.getVerifyMode());
            if (original.isStartTls()) ssl.startTls();
        }
        // 不继承连接池或共享连接：断连即终止当前尝试，关闭时销毁物理连接，避免 WATCH 丢失和队列残留。
        var client = builder.build();
        if (source.getSentinelConfiguration() != null) {
            factory = new LettuceConnectionFactory(source.getSentinelConfiguration(), client);
        } else if (source.getSocketConfiguration() != null) {
            factory = new LettuceConnectionFactory(source.getSocketConfiguration(), client);
        } else {
            factory = new LettuceConnectionFactory(source.getStandaloneConfiguration(), client);
        }
        factory.setShareNativeConnection(false);
        factory.afterPropertiesSet();
        factory.start();
        template = new StringRedisTemplate(factory);
    }

    StringRedisTemplate template() { return template; }

    @Override
    public void destroy() { factory.destroy(); }
}
