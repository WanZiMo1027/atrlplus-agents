package com.yuntian.dto;

public record AgentChatResponse(
        boolean shouldReply,
        String reply
) {
}