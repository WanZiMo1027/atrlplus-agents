package com.yuntian.memory.dto;

/**
 * 创建自定义 Memory Prompt 请求
 */
public record MemoryPromptCreateRequest(
        String name,
        String layer,
        String prompt
) {
}
