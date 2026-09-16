package com.yuntian.config;

import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.skills.ClassPathSkillLoader;
import dev.langchain4j.skills.Skills;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 从应用资源加载 Skill，并接入 Spring 自动装配的 AI Service。 */
@Configuration
public class SkillConfig {

    @Bean
    public Skills qqSkills() {
        // 使用 classpath 路径，兼容开发目录和打包后的 JAR，不依赖源码目录。
        return Skills.from(ClassPathSkillLoader.loadSkills("skills"));
    }

    @Bean
    public ToolProvider qqSkillToolProvider(Skills qqSkills) {
        // @AiService 自动装配唯一的 ToolProvider，暴露激活及按需读取参考文件的工具。
        return qqSkills.toolProvider();
    }
}
