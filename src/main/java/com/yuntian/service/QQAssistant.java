package com.yuntian.service;


import com.yuntian.dto.AgentDecision;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.service.spring.AiService;

@AiService
public interface QQAssistant {

    // 每轮只注入本次检索到的长期记忆，固定人格与协议统一由 system.txt 管理。
    @SystemMessage(fromResource = "/system.txt")
    AgentDecision chat(
            @MemoryId String conversationId,
            @V("longTermMemory") String longTermMemory,
            @UserMessage String message
    );
}
