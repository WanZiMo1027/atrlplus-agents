package com.yuntian.config;

import com.yuntian.context.ContextProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.Map;


@Configuration
public class LlmConfig {

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Bean
    @Primary
    public ChatModel chatModel(ContextProperties context){


        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .customParameters(Map.of("thinking",Map.of("type", "enabled")))
                .returnThinking(false)
                .sendThinking(false)
                .maxTokens(context.getOutputReservedTokens())
                .timeout(context.getModelTimeout())
                .maxRetries(0)
                .logRequests(false)
                // 正常日志不输出完整 reasoning_content；无效决策仅由业务层记录结束状态及 Token 用量。
                .logResponses(true)
                .build();


    }

    @Bean("contextSummaryModel")
    public ChatModel contextSummaryModel(ContextProperties context,
            @Value("${agent.context.summary-model.api-key:${langchain4j.open-ai.chat-model.api-key}}") String summaryKey,
            @Value("${agent.context.summary-model.base-url:${langchain4j.open-ai.chat-model.base-url}}") String summaryUrl,
            @Value("${agent.context.summary-model.model-name:${langchain4j.open-ai.chat-model.model-name}}") String summaryModel) {
        // 摘要模型与角色模型实例隔离，不携带 ChatMemory、人物设定或回复决策协议。
        return OpenAiChatModel.builder().apiKey(summaryKey).baseUrl(summaryUrl).modelName(summaryModel)
                .temperature(0.0).customParameters(Map.of("thinking", Map.of("type", "disabled")))
                .maxTokens(context.getMaxSummaryTokens()).timeout(context.getCompactorTimeout()).maxRetries(0)
                .logRequests(false).logResponses(false).build();
    }

}
