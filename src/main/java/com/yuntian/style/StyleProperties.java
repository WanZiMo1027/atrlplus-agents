package com.yuntian.style;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;
import java.util.*;

@Getter @Setter
@ConfigurationProperties("agent.style-learning")
public class StyleProperties {
    private boolean enabled;
    private List<String> conversations = new ArrayList<>();
    private Set<String> botSenderIds = new HashSet<>();
    private Set<String> botAddressNames = new HashSet<>(Set.of("小鱼", "大肥鱼", "肥鱼肥鱼"));
    private int messageThreshold = 40;
    private int maxNewMessages = 80;
    private int overlapMessages = 20;
    private Duration maxWait = Duration.ofMinutes(15);
    private int inputBudget = 32768;
    private int outputTokens = 4096;
    private Duration modelTimeout = Duration.ofSeconds(60);
    private Duration leaseDuration = Duration.ofMinutes(2);
    private String schema = "agent_style";
    private String extractorVersion = "v3";
    private String embeddingTemplateVersion = "v1";
    private Database database = new Database();
    private Model extractionModel = new Model();
    private Model embedding = new Model();
    private Reply reply = new Reply();

    @Getter @Setter public static class Reply {
        private boolean enabled;
        private Duration timeout = Duration.ofSeconds(5);
        private Duration sceneTimeout = Duration.ofSeconds(3);
        private int inputBudget = 8192;
        private int sampleBudget = 4096;
        private int limit = 3;
        private Model model = new Model();
        public void validate() {
            if (timeout == null || sceneTimeout == null || timeout.isNegative() || timeout.isZero()
                    || sceneTimeout.isNegative() || sceneTimeout.isZero() || sceneTimeout.compareTo(timeout) > 0
                    || sceneTimeout.compareTo(Duration.ofSeconds(3)) > 0
                    || timeout.compareTo(Duration.ofSeconds(5)) > 0 || inputBudget < 2048 || inputBudget > 32768
                    || sampleBudget < 0 || sampleBudget > 16384 || limit < 1 || limit > 3)
                throw new IllegalArgumentException("实时风格参考配置无效，总等待不得超过5秒");
        }
    }

    @Getter @Setter public static class Database {
        private String url = "";
        private String username = "postgres";
        private String password = "";
    }
    @Getter @Setter public static class Model {
        private String baseUrl = "";
        private String apiKey = "";
        private String modelName = "";
    }
    public void validate() {
        if (reply.isEnabled()) reply.validate();
        if (!schema.matches("[a-z][a-z0-9_]{0,47}") || database.url.isBlank()
                || embedding.baseUrl.isBlank() || embedding.apiKey.isBlank() || embedding.modelName.isBlank()
                || conversations.isEmpty() || conversations.stream().anyMatch(c -> !c.matches("qq:[0-9]+:group:[0-9]+"))
                || messageThreshold < 1 || maxNewMessages < 1 || overlapMessages < 0 || overlapMessages > 100
                || outputTokens < 1 || inputBudget < outputTokens + 2048 || maxWait.isNegative()
                || modelTimeout.isNegative() || modelTimeout.isZero()
                || leaseDuration.compareTo(modelTimeout.plusSeconds(15)) < 0
                || extractorVersion.isBlank() || embeddingTemplateVersion.isBlank()) {
            throw new IllegalArgumentException("风格学习配置无效，请检查白名单、模型、数据库和预算");
        }
    }
    public void requireConversation(String id) {
        if (id == null || !conversations.contains(id)) throw new IllegalArgumentException("会话不在学习白名单中");
    }
}
