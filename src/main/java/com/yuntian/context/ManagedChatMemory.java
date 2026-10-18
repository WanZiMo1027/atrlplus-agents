package com.yuntian.context;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;

/** 无自动淘汰、无独立写入：原文和最终决策仅由 ContextManager / AgentService 持久化。 */
public final class ManagedChatMemory implements ChatMemory {
    private final Object memoryId;
    private final ContextManager manager;

    public ManagedChatMemory(Object memoryId, ContextManager manager) { this.memoryId = memoryId; this.manager = manager; }
    @Override public Object id() { return memoryId; }

    @Override public void add(ChatMessage message) {
        var session = manager.session(memoryId);
        if (message instanceof SystemMessage system) session.acceptSystem(system);
        // UserMessage 已在调用模型前保存；AiMessage 还是未过滤的决策 JSON，两者都禁止自动入库。
    }

    @Override public List<ChatMessage> messages() { return manager.session(memoryId).memoryMessages(); }
    @Override public void clear() { throw new UnsupportedOperationException("不能清空尚未可靠归档的原始上下文"); }
}
