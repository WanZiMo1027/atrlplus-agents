package com.yuntian.style;

import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.*;
import dev.langchain4j.model.output.FinishReason;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuntian.style.StyleTypes.*;

/** 仅识别可迁移场景，不扮演小鱼、不决定是否发言，也不让机器人计划混入输入。 */
public class StyleSceneAnalyzer {
    public record Scene(boolean usable, String scene, String intent, String emotion, String seriousness, String interaction) {
        public String embeddingText() { return new Fragment("",scene,intent,emotion,seriousness,interaction,List.of(),List.of()).embeddingText(); }
    }
    public record Analysis(Scene scene, Integer inputTokens, Integer outputTokens) {}
    private final ChatModel model;
    private final ObjectMapper json;
    private final StyleProperties properties;
    private final String rules;
    public StyleSceneAnalyzer(ChatModel model, ObjectMapper json, StyleProperties properties) {
        this.model=model;this.json=json;this.properties=properties;
        try { rules=new org.springframework.core.io.ClassPathResource("prompts/style-scene.txt").getContentAsString(StandardCharsets.UTF_8); }
        catch(Exception e) { throw new IllegalStateException("STYLE_SCENE_RESOURCE_MISSING"); }
    }
    public String input(List<Source> history, List<Source> current) {
        var old = new ArrayList<>(history.stream().filter(Source::human).toList());
        if (old.size()>12) old=new ArrayList<>(old.subList(old.size()-12,old.size()));
        // 当前批次完整保留；只删除旧背景，不截断原话或借用摘要、L1 和回复计划。
        while (true) {
            var payload=new LinkedHashMap<String,Object>();payload.put("history",project(old));payload.put("current",project(current));
            try {
                String body=json.writeValueAsString(payload);
                if ((long)rules.getBytes(StandardCharsets.UTF_8).length+body.getBytes(StandardCharsets.UTF_8).length+512+256<=properties.getReply().getInputBudget()) return body;
            }catch(Exception e){throw new IllegalStateException("STYLE_SCENE_INPUT_INVALID");}
            if (old.isEmpty()) throw new IllegalStateException("STYLE_SCENE_INPUT_OVER_BUDGET");
            old.remove(0);
        }
    }
    private List<Map<String,Object>> project(List<Source> sources) {
        return sources.stream().map(s->{Map<String,Object> item=new LinkedHashMap<>();
            item.put("sender",s.senderId()==null?"unknown:"+s.eventId():s.senderId());item.put("human",s.human());
            item.put("text",s.text());item.put("background",s.background());return item;}).toList();
    }
    public Analysis analyze(String input) {
        var response=model.chat(ChatRequest.builder().messages(SystemMessage.from(rules),UserMessage.from(input))
                .parameters(ChatRequestParameters.builder().maxOutputTokens(512).build()).build());
        if(response==null||response.aiMessage()==null||response.aiMessage().text()==null||response.aiMessage().hasToolExecutionRequests()
                ||response.metadata()==null||response.metadata().finishReason()!=FinishReason.STOP) throw new IllegalStateException("STYLE_SCENE_COMPLETION_INVALID");
        var usage=response.metadata().tokenUsage();
        if(usage!=null&&usage.outputTokenCount()!=null&&usage.outputTokenCount()>512) throw new IllegalStateException("STYLE_SCENE_OUTPUT_OVER_BUDGET");
        Scene scene;
        try {
            JsonNode node=json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.aiMessage().text());
            if(!node.isObject()||node.size()!=6||!node.path("usable").isBoolean()) throw new IllegalArgumentException();
            for(String key:List.of("scene","intent","emotion","seriousness","interaction")) if(!node.path(key).isTextual()) throw new IllegalArgumentException();
            // 严格字段集，未知字段和缺失字段都不能悄悄变成默认值。
            scene=json.readerFor(Scene.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).readValue(node);
        }catch(Exception e){throw new IllegalStateException("STYLE_SCENE_JSON_INVALID");}
        if(scene.scene().length()>300||scene.emotion().length()>32||scene.interaction().length()>200
                ||!INTENTS.contains(scene.intent())||!SERIOUSNESS.contains(scene.seriousness())
                ||(scene.usable()&&(scene.scene().isBlank()||scene.interaction().isBlank()||scene.emotion().isBlank()
                    ||scene.emotion().equals("不明确")||scene.seriousness().equals("不明确"))))
            throw new IllegalStateException("STYLE_SCENE_LABELS_INVALID");
        return new Analysis(scene,usage==null?null:usage.inputTokenCount(),usage==null?null:usage.outputTokenCount());
    }
}
