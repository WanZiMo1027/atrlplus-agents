package com.yuntian.dto;

/**
 * Agent 聊天接口响应。
 * @param shouldReply 是否需要发送回复
 * @param reply 文字回复，可为空字符串
 * @param meme 可空的表情语义标签
 */
public record AgentChatResponse(
        boolean shouldReply,
        String reply,
        String meme
) {
}
