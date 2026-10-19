package com.yuntian.context;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 明确使用 UTF-8 加载人格资源，避免 Java 17 / Windows 默认字符集改变中文规则。 */
@Component
public class ContextPrompt {
    public static final String STYLE_OPEN = "<style_examples>\n";
    public static final String STYLE_CLOSE = "\n</style_examples>";
    private final String template;

    public ContextPrompt() {
        try { template = new ClassPathResource("system.txt").getContentAsString(StandardCharsets.UTF_8).replace("\r\n","\n"); }
        catch (IOException failure) { throw new IllegalStateException("无法加载 system.txt", failure); }
    }

    public String template() { return template; }

    public String render(String summary, String longTermMemory) {
        return render(summary,longTermMemory,"");
    }
    public String render(String summary, String longTermMemory, String styleExamples) {
        // 单次替换，背景中出现模板占位符时不会被再次解释为指令或另一个背景变量。
        var pattern = java.util.regex.Pattern.compile("\\{\\{(compressedContext|longTermMemory|styleExamples)}}");
        var matcher = pattern.matcher(template);
        return matcher.replaceAll(match -> java.util.regex.Matcher.quoteReplacement(
                switch(match.group(1)) {case "compressedContext" -> summary;case "styleExamples" -> styleExamples;default -> longTermMemory;}));
    }
    public String withoutStyle(String rendered,String examples) {
        return examples.isEmpty()?rendered:rendered.replace(STYLE_OPEN+examples+STYLE_CLOSE,STYLE_OPEN+STYLE_CLOSE);
    }
}
