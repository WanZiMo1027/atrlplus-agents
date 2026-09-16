package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * L0会话添加请求
 * @param sessionId 会话ID
 * @param messages 消息列表
 * @param teamId 团队ID
 * @param agentId 代理ID
 * @param userId 用户ID
 * */
public record ConversationAddRequest(
        @JsonProperty("session_id")
        String sessionId,

        List<MemoryMessage> messages,

        @JsonProperty("team_id")
        String teamId,

        @JsonProperty("agent_id")
        String agentId,

        @JsonProperty("user_id")
        String userId
) {
}
