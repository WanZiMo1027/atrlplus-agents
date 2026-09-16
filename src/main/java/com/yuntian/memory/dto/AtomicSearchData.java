package com.yuntian.memory.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;

/**
 * 搜索项
 */
public record AtomicSearchData(List<JsonNode> items) {
}
