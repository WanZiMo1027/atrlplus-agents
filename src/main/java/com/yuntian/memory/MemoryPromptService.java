package com.yuntian.memory;

import com.yuntian.memory.client.TencentMemoryClient;
import com.yuntian.memory.dto.MemoryPromptCreateData;
import com.yuntian.memory.dto.MemoryPromptCreateRequest;
import com.yuntian.memory.dto.MemoryPromptInitResponse;
import com.yuntian.memory.dto.MemoryPromptListData;
import com.yuntian.memory.dto.MemoryPromptRecord;
import com.yuntian.memory.dto.MemoryPromptSetData;
import com.yuntian.memory.dto.MemoryPromptSetRequest;
import com.yuntian.memory.dto.MemoryPromptUpdateData;
import com.yuntian.memory.dto.MemoryPromptUpdateRequest;
import com.yuntian.memory.dto.PromptView;
import com.yuntian.memory.dto.PublishPromptRequest;
import com.yuntian.memory.dto.MemoryResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
            // 管理面板发布过自定义 Prompt 后，手工初始化不能再把 classpath 模板绑回去。
            PromptView active = effectivePrompt();
            if (!active.memoryPromptId().startsWith("builtin:")) {
                return new MemoryPromptInitResponse(
                        active.memoryPromptId(), false, 0, teamId, agentId, PROMPT_LAYER);
            }
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

    /** 查询当前 Agent 实际生效的 L1 Prompt。 */
    public PromptView effectivePrompt() {
        MemoryPromptRecord record = requireData("查询生效 L1 Prompt",
                tencentMemoryClient.getEffectiveMemoryPrompt(
                        serviceId, userKey, authorization(), PROMPT_LAYER, teamId, agentId));
        String id = record.memoryPromptId() == null ? "builtin:l1" : record.memoryPromptId();
        return new PromptView(id, record.version(),
                record.prompt() == null ? "" : record.prompt(),
                id.startsWith("builtin:") ? "builtin" : "custom");
    }

    /** 提供项目自带模板供面板编辑；读取模板不会绑定或修改远端 Prompt。 */
    public String projectTemplate() {
        try {
            return readPrompt();
        } catch (IOException exception) {
            throw new IllegalStateException("读取 L1 Prompt 项目模板失败", exception);
        }
    }

    /** 发布前比较远端版本，避免多个管理端或外部操作互相覆盖。 */
    public synchronized PromptView publish(PublishPromptRequest request) {
        String content = normalizePrompt(request.content());
        if (content.isBlank() || content.length() > 10000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Prompt 内容需为 1 到 10000 字");
        }
        PromptView current = effectivePrompt();
        if (!current.memoryPromptId().equals(request.expectedMemoryPromptId())
                || current.version() != request.expectedVersion()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "L1 Prompt 已在其他位置变更");
        }
        if (content.equals(normalizePrompt(current.content()))) {
            return current;
        }
        if (current.memoryPromptId().startsWith("builtin:")) {
            // 首次发布重试时复用已创建但尚未绑定的 Prompt，避免消耗实例配额。
            MemoryPromptRecord existing = findExistingPrompt(content);
            String id = existing == null ? createPrompt(content) : existing.memoryPromptId();
            applyPrompt(id);
        } else {
            MemoryPromptUpdateData updated = requireData("更新 L1 Prompt",
                    tencentMemoryClient.updateMemoryPrompt(serviceId, userKey, authorization(),
                            new MemoryPromptUpdateRequest(current.memoryPromptId(), content)));
            if (updated.memoryPromptId() == null || updated.memoryPromptId().isBlank()) {
                throw new IllegalStateException("更新 L1 Prompt 响应缺少 memory_prompt_id");
            }
        }
        return effectivePrompt();
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
