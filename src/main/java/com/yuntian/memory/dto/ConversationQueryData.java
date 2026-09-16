package com.yuntian.memory.dto;

import java.util.List;

/**
 * L0会话查询数据
 * @param messages 会话项列表
 * @param total 总数量
 */
public record ConversationQueryData(
        List<ConversationItem> messages,
        int total
) {
}
