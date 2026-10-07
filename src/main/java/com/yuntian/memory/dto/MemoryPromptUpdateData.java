package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MemoryPromptUpdateData(
        @JsonProperty("memory_prompt_id") String memoryPromptId,
        int version,
        @JsonProperty("updated_at_ms") long updatedAtMs
) {
}
