package com.yuntian.context;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.Set;

/** 在结构化解析前验证主模型输出；thinking 内容不作为群聊决策或发言。 */
public final class DecisionChatModel implements ChatModel {
    private final ChatModel delegate;

    public DecisionChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override public ChatResponse doChat(ChatRequest request) {
        var response = delegate.chat(request);
        var metadata = response == null ? null : response.metadata();
        var finishReason = metadata == null ? null : metadata.finishReason();
        // 即使截断后恰好留下可解析的 JSON，也不能当作完整决策；不读取或回退到 reasoning_content。
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank() || response.aiMessage().hasToolExecutionRequests()
                || (finishReason != null && finishReason != FinishReason.STOP)) {
            var usage = metadata == null ? null : metadata.tokenUsage();
            // 异常只携带结束状态和用量，不记录模型思考、草稿或用户正文。
            throw new InvalidDecisionOutputException("finishReason=" + finishReason
                    + ", inputTokens=" + (usage == null ? null : usage.inputTokenCount())
                    + ", outputTokens=" + (usage == null ? null : usage.outputTokenCount()));
        }
        return response;
    }

    @Override public ChatRequestParameters defaultRequestParameters() { return delegate.defaultRequestParameters(); }
    @Override public ModelProvider provider() { return delegate.provider(); }
    @Override public Set<Capability> supportedCapabilities() { return delegate.supportedCapabilities(); }

    public static final class InvalidDecisionOutputException extends RuntimeException {
        private InvalidDecisionOutputException(String metadata) { super(metadata); }
    }
}
