package com.yuntian.style;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/** 单调时钟覆盖排队、网络和数据库；调用方另用 Future 限制等待，迟到结果不回写会话。 */
public record StyleReplyDeadline(long endNanos) {
    public static StyleReplyDeadline after(Duration timeout) { return new StyleReplyDeadline(System.nanoTime() + timeout.toNanos()); }
    public Duration remaining() {
        long left = endNanos - System.nanoTime();
        if (left <= 0 || Thread.currentThread().isInterrupted()) throw new IllegalStateException("STYLE_REPLY_TIMEOUT");
        return Duration.ofNanos(left);
    }
    public int sqlSeconds() { return (int) Math.max(1, (remaining().toNanos() + TimeUnit.SECONDS.toNanos(1) - 1) / TimeUnit.SECONDS.toNanos(1)); }
}
