package com.yuntian.service;


import com.yuntian.dto.AgentDecision;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface QQAssistant {

    // 每轮注入 Skill 目录及长期记忆；详细行为由 activate_skill 返回。
    @SystemMessage(fromResource = "/system.txt")
    AgentDecision chat(
            @MemoryId String conversationId,
            @V("longTermMemory") String longTermMemory,
            @V("availableSkills") String availableSkills,
            @UserMessage String message
    );
}
