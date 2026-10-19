package com.yuntian.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static com.yuntian.style.StyleTypes.*;

/** 场景与原话按匿名角色投影；来源身份和历史事件ID不进入主模型的引用候选。 */
public class StyleExampleRenderer {
    public record Example(long id, String json) {}
    private final ObjectMapper json;
    public StyleExampleRenderer(ObjectMapper json) { this.json=json; }
    public List<Example> examples(List<SearchHit> hits, int budget) {
        var result=new ArrayList<Example>();
        for(var hit:hits) {
            var f=hit.fragment();
            if(f.replies().isEmpty()||f.replies().stream().anyMatch(s->!s.human()||!s.eligible())) continue;
            var roles=new LinkedHashMap<String,String>();
            var payload=new LinkedHashMap<String,Object>();
            payload.put("scene",f.scene());payload.put("intent",f.intent());payload.put("emotion",f.emotion());
            payload.put("seriousness",f.seriousness());payload.put("interaction",f.interaction());
            payload.put("context",messages(f.context(),roles));payload.put("replies",messages(f.replies(),roles));
            try {
                // 转义分隔符与模板花括号，不让原话伪造背景边界或被二次模板解释。
                String text=json.writeValueAsString(payload).replace("<","\\u003c").replace(">","\\u003e").replace("&","\\u0026")
                        .replace("{{","\\u007b\\u007b").replace("}}","\\u007d\\u007d");
                result.add(new Example(hit.id(),text));
                if(render(result).getBytes(StandardCharsets.UTF_8).length>budget) { result.remove(result.size()-1); break; }
            }catch(Exception e){throw new IllegalStateException("STYLE_EXAMPLE_SERIALIZATION_FAILED");}
        }
        return List.copyOf(result);
    }
    private List<Map<String,String>> messages(List<Source> input, Map<String,String> roles) {
        return input.stream().map(s->{String role=roles.computeIfAbsent(s.senderId()==null?"unknown:"+s.eventId():s.senderId(),k->"群友"+(roles.size()+1));
            return Map.of("speaker",role,"text",s.text());}).toList();
    }
    public static String render(List<Example> examples) {
        return examples.isEmpty()?"":"[\n"+String.join(",\n",examples.stream().map(Example::json).toList())+"\n]";
    }
}
