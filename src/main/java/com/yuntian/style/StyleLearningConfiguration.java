package com.yuntian.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntian.context.ContextSummaryStore;
import com.zaxxer.hikari.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.flywaydb.core.Flyway;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "agent.style-learning.enabled", havingValue = "true")
@EnableConfigurationProperties(StyleProperties.class)
@EnableScheduling
public class StyleLearningConfiguration {
    @Bean(destroyMethod = "close")
    public HikariDataSource styleDataSource(StyleProperties properties) {
        properties.validate();
        HikariConfig config = new HikariConfig(); var db = properties.getDatabase();
        config.setJdbcUrl(db.getUrl()); config.setUsername(db.getUsername()); config.setPassword(db.getPassword());
        config.setMaximumPoolSize(4); config.setMinimumIdle(0); config.setConnectionTimeout(5000);
        config.setPoolName("style-learning");
        return new HikariDataSource(config);
    }
    @Bean public Flyway styleFlyway(@Qualifier("styleDataSource") HikariDataSource source, StyleProperties properties) {
        // 仅迁移独立 schema，不为已有 public 表建立 baseline，不清理或接管其他服务的数据。
        Flyway flyway = Flyway.configure().dataSource(source).schemas(properties.getSchema()).defaultSchema(properties.getSchema())
                .locations("classpath:db/style").cleanDisabled(true).load();
        flyway.migrate(); return flyway;
    }
    @Bean @DependsOn("styleFlyway")
    public StyleRepository styleRepository(@Qualifier("styleDataSource") HikariDataSource source, ObjectMapper json, StyleProperties p) {
        return new StyleRepository(source, json, p);
    }
    @Bean("styleExtractionModel")
    public ChatModel styleExtractionModel(StyleProperties properties,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String fallbackKey,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String fallbackUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String fallbackModel) {
        var m = properties.getExtractionModel();
        return OpenAiChatModel.builder().apiKey(or(m.getApiKey(), fallbackKey)).baseUrl(or(m.getBaseUrl(), fallbackUrl))
                .modelName(or(m.getModelName(), fallbackModel)).temperature(0.0)
                .customParameters(Map.of("thinking", Map.of("type", "disabled"))).maxTokens(properties.getOutputTokens())
                .timeout(properties.getModelTimeout()).maxRetries(0).logRequests(false).logResponses(false).build();
    }
    @Bean public StyleSourceReader styleSourceReader(ObjectMapper json, StyleProperties p) { return new StyleSourceReader(json, p); }
    @Bean public StyleExtractor styleExtractor(@Qualifier("styleExtractionModel") ChatModel model, ObjectMapper json, StyleProperties p) throws Exception {
        try (var input = new ClassPathResource("prompts/style-extraction.txt").getInputStream()) {
            return new StyleExtractor(model, json, p, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    @Bean public StyleEmbeddingClient styleEmbeddingClient(StyleProperties p, ObjectMapper json) { return new StyleEmbeddingClient(p, json); }
    @Bean(destroyMethod = "shutdownNow") public ExecutorService styleWorkers() {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2), runnable -> {
            Thread thread = new Thread(runnable, "style-learning-worker"); thread.setDaemon(true); return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean public StyleLearningService styleLearningService(StyleProperties p, ContextSummaryStore source, StyleSourceReader reader,
            StyleExtractor extractor, StyleEmbeddingClient embedding, StyleRepository repository,
            @Qualifier("styleWorkers") ExecutorService workers) {
        return new StyleLearningService(p, source, reader, extractor, embedding, repository, workers);
    }
    private static String or(String value, String fallback) { return value.isBlank() ? fallback : value; }
}
