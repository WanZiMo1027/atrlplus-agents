package com.yuntian.memory;

import com.yuntian.memory.client.TencentMemoryClient;
import com.yuntian.memory.dto.MemoryPromptCreateData;
import com.yuntian.memory.dto.MemoryPromptCreateRequest;
import com.yuntian.memory.dto.MemoryPromptInitResponse;
import com.yuntian.memory.dto.MemoryPromptListData;
import com.yuntian.memory.dto.MemoryPromptRecord;
import com.yuntian.memory.dto.MemoryPromptSetData;
import com.yuntian.memory.dto.MemoryPromptSetRequest;
import com.yuntian.memory.dto.MemoryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 腾讯 L1 Memory Prompt 的人工初始化服务。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MemoryPromptService {

    private static final String PROMPT_NAME = "qq-group-chat-l1-memory";
    private static final String PROMPT_LAYER = "l1";
    private static final String PROMPT_RESOURCE = "classpath:prompts/tencent-l1-qq-group.txt";
    private static final int QUERY_PAGE_SIZE = 100;
    private static final int MAX_PROMPT_COUNT = 500;

    private final TencentMemoryClient tencentMemoryClient;
    private final ResourceLoader resourceLoader;

    @Value("${tencent-memory.service-id}")
    private String serviceId;

    @Value("${tencent-memory.user-key}")
    private String userKey;

    @Value("${tencent-memory.team-id}")
    private String teamId;

    @Value("${tencent-memory.agent-id}")
    private String agentId;

    @Value("${tencent-memory.bearer-token:local}")
    private String bearerToken;

    /**
     * 查询或创建 QQ 群聊 L1 Prompt，并绑定到当前配置的 Agent。
     */
    public synchronized MemoryPromptInitResponse initializeL1Prompt() {
        String memoryPromptId = null;
        try {
            String prompt = readPrompt();

            // 先分页查询并精确匹配，保证应用重启后再次初始化不会重复创建。
            MemoryPromptRecord existingPrompt = findExistingPrompt(prompt);
            boolean created = existingPrompt == null;
            if (created) {
                memoryPromptId = createPrompt(prompt);
            } else {
                memoryPromptId = existingPrompt.memoryPromptId();
                log.info("复用已有 Prompt：memoryPromptId={}", memoryPromptId);
            }

            // 无论 Prompt 是新建还是复用，都执行幂等绑定以修复可能中断的初始化。
            int affected = applyPrompt(memoryPromptId);
            log.info("绑定 L1 Prompt 成功：teamId={} agentId={} memoryPromptId={}",
                    teamId, agentId, memoryPromptId);
            return new MemoryPromptInitResponse(
                    memoryPromptId, created, affected, teamId, agentId, PROMPT_LAYER);
        } catch (Exception exception) {
            log.error("初始化 L1 Prompt 失败：teamId={} agentId={} memoryPromptId={}",
                    teamId, agentId, memoryPromptId, exception);
            throw new IllegalStateException("初始化 TencentDB L1 Memory Prompt 失败", exception);
        }
    }

    private MemoryPromptRecord findExistingPrompt(String prompt) {
        for (int offset = 0; offset < MAX_PROMPT_COUNT; offset += QUERY_PAGE_SIZE) {
            MemoryResponse<MemoryPromptListData> response = tencentMemoryClient.getMemoryPrompts(
                    serviceId, userKey, authorization(), PROMPT_LAYER, QUERY_PAGE_SIZE, offset, "desc");
            MemoryPromptListData data = requireData("查询 Memory Prompt", response);
            List<MemoryPromptRecord> items = data.items() == null ? List.of() : data.items();

            MemoryPromptRecord match = items.stream()
                    .filter(item -> PROMPT_NAME.equals(item.name()))
                    .filter(item -> PROMPT_LAYER.equals(item.layer()))
                    .filter(item -> prompt.equals(normalizePrompt(item.prompt())))
                    .findFirst()
                    .orElse(null);
            if (match != null) {
                return match;
            }
            if (items.size() < QUERY_PAGE_SIZE) {
                break;
            }
        }
        return null;
    }

    private String createPrompt(String prompt) {
        MemoryPromptCreateRequest request = new MemoryPromptCreateRequest(
                PROMPT_NAME, PROMPT_LAYER, prompt);
        MemoryResponse<MemoryPromptCreateData> response = tencentMemoryClient.createMemoryPrompt(
                serviceId, userKey, authorization(), request);
        MemoryPromptCreateData data = requireData("创建 Memory Prompt", response);
        if (data.memoryPromptId() == null || data.memoryPromptId().isBlank()) {
            throw new IllegalStateException("创建 Memory Prompt 响应缺少 memory_prompt_id");
        }
        log.info("创建 Prompt 成功：memoryPromptId={}", data.memoryPromptId());
        return data.memoryPromptId();
    }

    private int applyPrompt(String memoryPromptId) {
        MemoryPromptSetRequest request = new MemoryPromptSetRequest(
                "apply", memoryPromptId, teamId, List.of(agentId), PROMPT_LAYER);
        MemoryResponse<MemoryPromptSetData> response = tencentMemoryClient.setMemoryPrompt(
                serviceId, userKey, authorization(), request);
        return requireData("绑定 L1 Memory Prompt", response).affected();
    }

    private String readPrompt() throws IOException {
        Resource resource = resourceLoader.getResource(PROMPT_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("未找到 L1 Prompt 资源：" + PROMPT_RESOURCE);
        }
        try (var inputStream = resource.getInputStream()) {
            String prompt = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
            String normalizedPrompt = normalizePrompt(prompt);
            if (normalizedPrompt.isBlank()) {
                throw new IllegalStateException("L1 Prompt 资源内容为空：" + PROMPT_RESOURCE);
            }
            return normalizedPrompt;
        }
    }

    private <T> T requireData(String operation, MemoryResponse<T> response) {
        if (response == null) {
            throw new IllegalStateException(operation + "响应为空");
        }
        if (response.code() != 0) {
            throw new IllegalStateException(operation + " 失败：code=" + response.code()
                    + " message=" + response.message() + " requestId=" + response.requestId());
        }
        if (response.data() == null) {
            throw new IllegalStateException(operation + "响应缺少 data");
        }
        return response.data();
    }

    private String normalizePrompt(String prompt) {
        if (prompt == null) {
            return "";
        }
        return prompt.replace("\r\n", "\n").replace('\r', '\n').strip();
    }

    private String authorization() {
        return "Bearer " + bearerToken;
    }
}
