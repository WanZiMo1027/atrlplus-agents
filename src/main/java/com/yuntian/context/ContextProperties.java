package com.yuntian.context;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/** 预算包含输入、协议和输出预留；存储容量继续使用第一阶段 storage 配置。 */
@Getter
@Setter
@ConfigurationProperties("agent.context")
public class ContextProperties {
    private boolean enabled = true;
    private int tokenBudget = 65536;
    private int compressionThreshold = 49152;
    private int keepRecentMessages = 20;
    // 摘要模型的实际输出 Token 上限；调用方缺少有效 usage 时使用 UTF-8 字节数保守兜底。
    private int maxSummaryTokens = 2048;
    private int maxLongTermMemoryTokens = 4096;
    // thinking 与最终正文共享此输出额度，发送上限和上下文预留必须一致。
    private int outputReservedTokens = 8192;
    private int protocolReservedTokens = 512;
    private int imageTokenBudget = 4096;
    private int compactorTokenBudget = 32768;
    private int maxCompactionsPerRequest = 4;
    private Duration leaseDuration = Duration.ofMinutes(2);
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration compactorTimeout = Duration.ofSeconds(30);

    public void validate() {
        if (tokenBudget <= 0 || compressionThreshold <= 0 || compressionThreshold > tokenBudget
                || keepRecentMessages < 20 || maxSummaryTokens <= 0 || maxSummaryTokens > 65536
                || maxLongTermMemoryTokens < 0 || outputReservedTokens <= 0 || protocolReservedTokens < 0
                || tokenBudget <= (long) maxSummaryTokens + outputReservedTokens + protocolReservedTokens
                || imageTokenBudget <= 0 || compactorTokenBudget <= (long) maxSummaryTokens + protocolReservedTokens
                || maxCompactionsPerRequest < 1 || leaseDuration == null || modelTimeout == null
                || compactorTimeout == null || modelTimeout.isNegative() || modelTimeout.isZero()
                || compactorTimeout.isNegative() || compactorTimeout.isZero()
                || leaseDuration.compareTo(modelTimeout.plusSeconds(10)) <= 0
                || leaseDuration.compareTo(compactorTimeout.plusSeconds(10)) <= 0) {
            throw new IllegalArgumentException("上下文预算或租约配置无效；至少保留 20 条，租期须覆盖模型超时及提交余量");
        }
    }
}
