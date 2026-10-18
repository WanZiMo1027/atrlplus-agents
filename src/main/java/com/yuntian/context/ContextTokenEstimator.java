package com.yuntian.context;

import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

/** 不增加 tokenizer 依赖：文本按 UTF-8 字节保守估算，图片按配置的单图上界预留。 */
@Component
public class ContextTokenEstimator {
    private final ContextProperties properties;

    public ContextTokenEstimator(ContextProperties properties) { this.properties = properties; }

    public long text(String value) { return value == null ? 0 : value.getBytes(StandardCharsets.UTF_8).length; }

    public long messages(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage message : messages) {
            total += 16; // 角色、分隔符和消息封装，不能只计算正文。
            if (message instanceof SystemMessage system) total += text(system.text());
            else if (message instanceof AiMessage ai) total += text(ai.text());
            else if (message instanceof UserMessage user) {
                for (Content content : user.contents()) {
                    total += 8;
                    if (content instanceof TextContent part) total += text(part.text());
                    else if (content instanceof ImageContent) total += properties.getImageTokenBudget();
                    else throw new BudgetExceededException("当前媒体类型没有可用的预算估算规则");
                }
            } else throw new BudgetExceededException("当前上下文不支持工具或未知消息类型");
        }
        return total;
    }

    /** 实际发送前再次核算；包含 LangChain4j 自动追加的结构化输出格式。 */
    public long total(List<ChatMessage> messages) {
        return messages(messages) + properties.getOutputReservedTokens() + properties.getProtocolReservedTokens();
    }

    public String limit(String text, int budget) {
        if (text(text) <= budget) return text == null ? "" : text;
        String marker = "\n[背景因预算截断]";
        long available = Math.max(0, budget - text(marker));
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < text.length();) {
            int codePoint = text.codePointAt(i);
            String part = new String(Character.toChars(codePoint));
            long bytes = text(part);
            if (bytes > available) break;
            result.append(part);
            available -= bytes;
            i += Character.charCount(codePoint);
        }
        if (text(marker) <= budget) result.append(marker);
        return result.toString();
    }

    public static class BudgetExceededException extends IllegalStateException {
        public BudgetExceededException(String message) { super(message); }
    }
}
