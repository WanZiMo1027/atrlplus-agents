package com.yuntian.memory.client;

import com.yuntian.memory.dto.*;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * 腾讯记忆客户端：携带服务 ID、用户密钥及 v3 路由要求的 Bearer Header。
 */
@FeignClient(
        name = "tencent-memory",
        url = "${tencent-memory.base-url}"
)
public interface TencentMemoryClient {

    /**
     * L0添加会话
     * @param serviceId 服务ID
     * @param userKey 用户密钥
     * @param authorization Bearer 鉴权头
     * @param request 请求
     * @return 响应
     */
    @PostMapping("/v3/conversation/add")
    MemoryResponse<ConversationAddData> addConversation(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestBody ConversationAddRequest request
    );

/**
     * L0查询会话
     * @param serviceId 服务ID
     * @param userKey 用户密钥
     * @param authorization Bearer 鉴权头
     * @param request 请求
     * @return 响应
     */
    @PostMapping("/v3/conversation/query")
    MemoryResponse<ConversationQueryData> queryConversation(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestBody ConversationQueryRequest request
    );

    /**
     * L1搜索
     * @param serviceId 服务ID
     * @param userKey 用户密钥
     * @param authorization Bearer 鉴权头
     * @param request 请求
     * @return 响应
     */
    @PostMapping("/v3/atomic/search")
    MemoryResponse<AtomicSearchData> searchAtomic(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestBody AtomicSearchRequest request
    );

    /**
     * 创建自定义 Memory Prompt
     */
    @PostMapping("/v3/memory-prompt/create")
    MemoryResponse<MemoryPromptCreateData> createMemoryPrompt(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestBody MemoryPromptCreateRequest request
    );

    /**
     * 将自定义 Memory Prompt 应用到指定 Agent
     */
    @PostMapping("/v3/memory-prompt/set")
    MemoryResponse<MemoryPromptSetData> setMemoryPrompt(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestBody MemoryPromptSetRequest request
    );

    /**
     * 分页查询 Memory Prompt，用于初始化时避免重复创建
     */
    @GetMapping("/v3/memory-prompt/get")
    MemoryResponse<MemoryPromptListData> getMemoryPrompts(
            @RequestHeader("x-tdai-service-id") String serviceId,
            @RequestHeader("x-tdai-user-key") String userKey,
            @RequestHeader("Authorization") String authorization,
            @RequestParam("layer") String layer,
            @RequestParam("limit") int limit,
            @RequestParam("offset") int offset,
            @RequestParam("time_order") String timeOrder
    );
}
