package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * 应用或清除自定义 Memory Prompt 请求
 */
public record MemoryPromptSetRequest(
        String action,

        @JsonProperty("memory_prompt_id")
        String memoryPromptId,

        @JsonProperty("team_id")
        String teamId,

        @JsonProperty("agent_ids")
        List<String> agentIds,

        String layer
) {
}
