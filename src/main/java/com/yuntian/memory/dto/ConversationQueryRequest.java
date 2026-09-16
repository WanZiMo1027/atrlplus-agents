package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;


/**
 * L0 查询DTO
 * @param sessionId 会话ID
 * @param limit 限制数量
 * @param offset 偏移量
 * @param teamId 团队ID
 * @param agentId 代理ID
 * @param userId 用户ID
 */
public record ConversationQueryRequest(
        @JsonProperty("session_id")
        String sessionId,

        int limit,

        int offset,

        @JsonProperty("team_id")
        String teamId,

        @JsonProperty("agent_id")
        String agentId,

        @JsonProperty("user_id")
        String userId
) {
}
