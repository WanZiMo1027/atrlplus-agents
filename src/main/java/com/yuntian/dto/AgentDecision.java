package com.yuntian.dto;

import java.util.List;

/**
 * 聊天决策记录
 * @param shouldReply 是否回复
 * @param replies 回复内容
 * @param meme 可空的表情语义标签
 */
public record AgentDecision(
        boolean shouldReply,
        List<String> replies,
        String meme
) {
}
