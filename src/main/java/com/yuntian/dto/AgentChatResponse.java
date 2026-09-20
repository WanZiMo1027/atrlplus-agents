package com.yuntian.dto;

import java.util.List;

/**
 * Agent 聊天接口响应。
 * @param shouldReply 是否需要发送回复
 * @param replies 文字回复，可为空字符串
 * @param meme 可空的表情语义标签
 */
public record AgentChatResponse(
        boolean shouldReply,
        List<String> replies,
        String meme
) {
}
