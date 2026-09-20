package com.yuntian.dto;

import java.util.List;

/**
 * 聊天决策记录
 * @param shouldReply 是否回复
 * @param replies 回复内容列表
 * @param replyToEventId 回复消息 ID
 * @param meme 可空的表情语义标签
 */
public record AgentDecision(
        boolean shouldReply,
        List<String> replies,
        String replyToEventId,
        String meme
) {
}
