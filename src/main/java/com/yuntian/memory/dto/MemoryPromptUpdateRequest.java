package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** 更新已绑定的 L1 Prompt。 */
public record MemoryPromptUpdateRequest(
        @JsonProperty("memory_prompt_id") String memoryPromptId,
        String prompt
) {
}
