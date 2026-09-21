package com.yuntian.config;

import com.yuntian.memory.chat.RedisChatMemoryStore;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import lombok.Builder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
/**
 * 聊天内存配置类
 */
public class ChatMemoryConfig {

    /**
     * 为每一个 conversationId 创建独立的 ChatMemory
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(RedisChatMemoryStore redisChatMemoryStore) {

        return memoryId ->
                MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(10)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build();
    }
}