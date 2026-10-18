package com.yuntian.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 聊天消息项
 * @param eventId 事件ID
 * @param senderName 发送者名称
 * @param text 消息文本
 * @param images 图片列表
 * @param senderId 可选的稳定 QQ userId；旧请求缺省为空，不从昵称推断
 */
public record ChatMessageItem(
        String eventId,
        String senderName,
        String text,
        List<ChatImageItem> images,
        @JsonAlias("userId") @JsonInclude(JsonInclude.Include.NON_NULL) String senderId) {

        public ChatMessageItem {
            if (images == null) {
                images = List.of();
            }else {
                images = List.copyOf(images);
            }
        }

        public ChatMessageItem(String eventId, String senderName, String text) {
            this(eventId, senderName, text, List.of(), null);
        }

        /** 保留旧请求和旧 Redis JSON 的兼容性，缺少 QQ ID 时不从昵称猜测。 */
        public ChatMessageItem(String eventId, String senderName, String text, List<ChatImageItem> images) {
            this(eventId, senderName, text, images, null);
        }

}
