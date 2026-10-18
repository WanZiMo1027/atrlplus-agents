package com.yuntian.dto;

import java.util.List;

public record AgentChatRequest(
        String conversationId,
        List<ChatMessageItem> messages,
        Boolean replyEnabled) {
    public AgentChatRequest {
        // 旧请求未提供开关时默认继续回复；显式 false 才进入只记录模式。
        replyEnabled = !Boolean.FALSE.equals(replyEnabled);
    }

    public AgentChatRequest(String conversationId, List<ChatMessageItem> messages) {
        this(conversationId, messages, true);
    }
}
