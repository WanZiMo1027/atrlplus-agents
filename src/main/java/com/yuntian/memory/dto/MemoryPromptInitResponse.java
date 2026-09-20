package com.yuntian.memory.dto;

/**
 * L1 Memory Prompt 初始化结果
 */
public record MemoryPromptInitResponse(
        String memoryPromptId,
        boolean created,
        int affected,
        String teamId,
        String agentId,
        String layer
) {
}
