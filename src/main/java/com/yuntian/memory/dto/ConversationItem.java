package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * L0会话项
 * @param id 会话ID
 * @param version 会话版本
 * @param role 角色
 * @param content 内容
 * @param sessionId 会话ID
 * @param teamId 团队ID
 * @param agentId 代理ID
 * @param userId 用户ID
 * */
public record ConversationItem(
        String id,

        String version,

        String role,

        String content,

        @JsonProperty("session_id")
        String sessionId,

        @JsonProperty("team_id")
        String teamId,

        @JsonProperty("agent_id")
        String agentId,

        @JsonProperty("user_id")
        String userId
) {
}
