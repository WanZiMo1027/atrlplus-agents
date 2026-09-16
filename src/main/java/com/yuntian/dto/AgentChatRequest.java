package com.yuntian.dto;

import java.util.List;

public record AgentChatRequest(
        String conversationId,
        List<ChatMessageItem> messages) {
}