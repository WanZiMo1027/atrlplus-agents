package com.yuntian.service;


import com.yuntian.dto.AgentDecision;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

import java.util.List;

public interface QQAssistant {

    // ChatMemoryConfig 以 UTF-8 加载 system.txt；摘要和 L1 是独立背景，不保存成聊天消息。
    AgentDecision chat(
            @MemoryId String conversationId,
            @V("longTermMemory") String longTermMemory,
            @V("compressedContext") String compressedContext,
            @UserMessage List<Content> message
    );
}
