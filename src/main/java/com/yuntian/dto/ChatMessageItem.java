package com.yuntian.dto;

import java.util.List;

/**
 * 聊天消息项
 * @param eventId 事件ID
 * @param senderName 发送者名称
 * @param text 消息文本
 * @param images 图片列表
 */
public record ChatMessageItem(
        String eventId,
        String senderName,
        String text,
        List<ChatImageItem> images) {

        public ChatMessageItem {
            if (images == null) {
                images = List.of();
            }else {
                images = List.copyOf(images);
            }
        }

        public ChatMessageItem(String eventId, String senderName, String text) {
            this(eventId, senderName, text, List.of());
        }

}
