package com.yuntian.memory.dto;

/**
 * 内存消息
 * @param role 角色
 * @param content 内容
 * */
public record MemoryMessage(
        String role,
        String content
) {
}
