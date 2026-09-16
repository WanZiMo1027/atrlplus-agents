package com.yuntian.memory.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * 内存响应
 * @param <T> 数据类型
 * @param code 状态码
 * @param message 状态消息
 * @param requestId 请求ID
 * @param data 数据
 * */
public record MemoryResponse<T>(
        int code,
        String message,
        @JsonProperty("request_id")
        String requestId,
        T data) {
}
