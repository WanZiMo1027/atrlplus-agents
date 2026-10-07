package com.yuntian.memory.dto;

/** 当前 Agent 实际生效的 L1 Prompt。 */
public record PromptView(String memoryPromptId, int version, String content, String source) {
}
