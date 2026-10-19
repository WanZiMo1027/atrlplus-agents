package com.yuntian.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import java.util.Map;
import java.util.concurrent.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Configuration(proxyBeanMethods=false)
@ConditionalOnProperty(name={"agent.style-learning.enabled","agent.style-learning.reply.enabled"},havingValue="true")
public class StyleReplyConfiguration {
    @Bean("styleSceneModel")
    public ChatModel styleSceneModel(StyleProperties p,
            @Value("${langchain4j.open-ai.chat-model.api-key}") String key,
            @Value("${langchain4j.open-ai.chat-model.base-url}") String url,
            @Value("${langchain4j.open-ai.chat-model.model-name}") String name) {
        p.getReply().validate();var m=p.getReply().getModel();var fallback=p.getExtractionModel();
        return OpenAiChatModel.builder().apiKey(first(m.getApiKey(),fallback.getApiKey(),key)).baseUrl(first(m.getBaseUrl(),fallback.getBaseUrl(),url))
                .modelName(first(m.getModelName(),fallback.getModelName(),name)).temperature(0.0)
                .customParameters(Map.of("thinking",Map.of("type","disabled"))).maxTokens(512).timeout(p.getReply().getSceneTimeout())
                .maxRetries(0).logRequests(false).logResponses(false).build();
    }
    private String first(String a,String b,String c){return !a.isBlank()?a:!b.isBlank()?b:c;}
    @Bean(destroyMethod="shutdownNow") public ExecutorService styleReplyWorkers() {
        // 不排队也不CallerRuns；依赖变慢时最多占两个专用线程，群聊主流程立即回退。
        return new ThreadPoolExecutor(2,2,0,TimeUnit.SECONDS,new SynchronousQueue<>(),r->{var t=new Thread(r,"style-reply-worker");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean public StyleSceneAnalyzer styleSceneAnalyzer(@Qualifier("styleSceneModel") ChatModel model,ObjectMapper json,StyleProperties p){return new StyleSceneAnalyzer(model,json,p);}
    @Bean public StyleReplyService styleReplyService(StyleProperties p,StyleSceneAnalyzer scene,StyleEmbeddingClient embedding,
            StyleRepository repo,ObjectMapper json,@Qualifier("styleReplyWorkers") ExecutorService workers,StyleSourceReader reader) {
        log.info("正式回复风格参考已启用: groups={}, timeoutMs={}, sceneTimeoutMs={}, sampleBudget={}, limit={}",p.getConversations().size(),
                p.getReply().getTimeout().toMillis(),p.getReply().getSceneTimeout().toMillis(),p.getReply().getSampleBudget(),p.getReply().getLimit());
        return new StyleReplyService(p,scene,embedding,repo,new StyleExampleRenderer(json),workers,reader);
    }
}
