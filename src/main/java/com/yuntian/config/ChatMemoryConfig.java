package com.yuntian.config;

import com.yuntian.context.*;
import com.yuntian.service.QQAssistant;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContextProperties.class)
/**
 * 聊天内存配置类
 */
public class ChatMemoryConfig {

    /**
     * 为每一个 conversationId 创建独立的 ChatMemory
     */
    @Bean
    public ChatMemoryProvider chatMemoryProvider(ContextManager manager) {
        return memoryId -> new ManagedChatMemory(memoryId, manager);
    }

    @Bean
    public QQAssistant qqAssistant(@Qualifier("chatModel") ChatModel model, ChatMemoryProvider provider,
            ContextPrompt prompt, ContextManager manager) {
        // 显式绑定主模型，避免新增摘要 ChatModel 后自动装配歧义；UTF-8 模板仍由 @V 参数逐轮填充。
        return AiServices.builder(QQAssistant.class).chatModel(new DecisionChatModel(model)).chatMemoryProvider(provider)
                .systemMessageProvider(id -> prompt.template())
                .chatRequestTransformer(manager::guard).build();
    }
}
