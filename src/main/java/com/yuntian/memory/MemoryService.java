package com.yuntian.memory;

import com.yuntian.memory.client.TencentMemoryClient;
import com.yuntian.memory.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MemoryService {

    /**
     * 腾讯记忆客户端
     */
    private final TencentMemoryClient tencentMemoryClient;

    @Value("${tencent-memory.service-id}")
    private String serviceId;

    @Value("${tencent-memory.user-key}")
    private String userKey;

    @Value("${tencent-memory.team-id}")
    private String teamId;

    @Value("${tencent-memory.agent-id}")
    private String agentId;

    @Value("${tencent-memory.user-id}")
    private String userId;

    @Value("${tencent-memory.bearer-token:local}")
    private String bearerToken;


    /**
     * 保存会话到 L0
     */
    public MemoryResponse<ConversationAddData> saveConversation(String sessionId, String userMessage, String assistantMessage) {

        List<MemoryMessage> messages = new ArrayList<>();
        messages.add(new MemoryMessage("user", userMessage));
        // 如果有回复才保存assistantMessage
        if (!assistantMessage.isBlank() && assistantMessage != null){
            messages.add(new MemoryMessage("assistant", assistantMessage));
        }

        ConversationAddRequest request = new ConversationAddRequest(sessionId, messages, teamId, agentId, userId);

        return tencentMemoryClient.addConversation(serviceId, userKey, authorization(), request
        );
    }


    /**
     * 查询原始会话 L0
     */
    public MemoryResponse<ConversationQueryData> queryConversation(String sessionId) {

        ConversationQueryRequest request = new ConversationQueryRequest(sessionId, 20, 0, teamId, agentId, userId);

        return tencentMemoryClient.queryConversation(serviceId, userKey, authorization(), request);
    }


    /**
     * 查询 L1 原子记忆
     */
    public MemoryResponse<AtomicSearchData> searchMemory(String sessionId, String query) {

        AtomicSearchRequest request = new AtomicSearchRequest(query, 5, teamId, agentId, userId, sessionId);

        return tencentMemoryClient.searchAtomic(serviceId, userKey, authorization(), request);
    }



    /**
     * 构建 Bearer 鉴权头
     */
    private String authorization() {
        return "Bearer " + bearerToken;
    }
}