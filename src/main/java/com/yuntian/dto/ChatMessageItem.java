package com.yuntian.dto;

/**
 * 聊天消息项
 * @param eventId 事件ID
 * @param senderName 发送者名称
 * @param text 消息文本
 */
public record ChatMessageItem(String eventId, String senderName, String text) {
}
