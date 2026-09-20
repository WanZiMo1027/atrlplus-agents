package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 自定义 Memory Prompt 记录
 */
public record MemoryPromptRecord(
        @JsonProperty("memory_prompt_id")
        String memoryPromptId,

        String name,

        String layer,

        String prompt,

        int version,

        String status,

        @JsonProperty("created_at_ms")
        long createdAtMs,

        @JsonProperty("updated_at_ms")
        long updatedAtMs
) {
}
