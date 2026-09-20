package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 创建自定义 Memory Prompt 响应数据
 */
public record MemoryPromptCreateData(
        @JsonProperty("memory_prompt_id")
        String memoryPromptId,

        int version,

        @JsonProperty("created_at_ms")
        long createdAtMs
) {
}
