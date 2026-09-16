package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * L1搜索DTO
 * @param query
 * @param limit
 * @param teamId
 * @param agentId
 * @param userId
 */
public record AtomicSearchRequest(
        String query,

        int limit,

        @JsonProperty("team_id")
        String teamId,

        @JsonProperty("agent_id")
        String agentId,

        @JsonProperty("user_id")
        String userId,

        @JsonProperty("session_id")
        String sessionId
) {
}
