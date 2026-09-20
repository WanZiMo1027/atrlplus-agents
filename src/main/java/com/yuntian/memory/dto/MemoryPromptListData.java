package com.yuntian.memory.dto;

import java.util.List;

/**
 * Memory Prompt 分页查询响应数据
 */
public record MemoryPromptListData(List<MemoryPromptRecord> items) {
}
