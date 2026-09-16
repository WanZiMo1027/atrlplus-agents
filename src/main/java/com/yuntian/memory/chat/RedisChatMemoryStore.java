package com.yuntian.memory.chat;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

@Component
@RequiredArgsConstructor
public class RedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "qq:chat-memory:";

    /**
     * 短期记忆过期时间
     */
    private static final Duration TTL = Duration.ofHours(24);

    private final StringRedisTemplate redisTemplate;

    /**
     * 根据 memoryId 从 Redis 读取聊天记忆
     */
    @Override
    public List<ChatMessage> getMessages(Object memoryId) {

        String key = buildKey(memoryId);

        String json = redisTemplate
                .opsForValue()
                .get(key);

        if (json == null || json.isBlank()) {
            return List.of();
        }

        return messagesFromJson(json);
    }

    /**
     * 更新聊天记忆
     */
    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages){
        String key = buildKey(memoryId);
        String json = messagesToJson(messages);
        redisTemplate.opsForValue().set(key, json, TTL);
    }

    /**
     * 删除某个会话的聊天记忆
     */
    @Override
    public void deleteMessages(Object memoryId) {
        redisTemplate.delete(buildKey(memoryId));
    }

    private String buildKey(Object memoryId) {
        return KEY_PREFIX + memoryId;
    }
}
