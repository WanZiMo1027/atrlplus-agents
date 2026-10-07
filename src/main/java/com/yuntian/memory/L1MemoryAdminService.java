package com.yuntian.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.yuntian.memory.client.TencentMemoryClient;
import com.yuntian.memory.dto.MemoryResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Locale;

/** 只管理当前配置的 Agent 的 L1 原子记忆，不接受外部指定归属。 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "admin.internal.enabled", havingValue = "true")
public class L1MemoryAdminService {

    private static final Set<String> TYPES = Set.of("episodic", "persona", "instruction");
    private final TencentMemoryClient client;
    private final ObjectMapper mapper;

    @Value("${tencent-memory.service-id}") private String serviceId;
    @Value("${tencent-memory.user-key}") private String userKey;
    @Value("${tencent-memory.bearer-token:local}") private String bearerToken;
    @Value("${tencent-memory.team-id}") private String teamId;
    @Value("${tencent-memory.agent-id}") private String agentId;
    @Value("${tencent-memory.user-id}") private String userId;

    public JsonNode list(int limit, int offset, String type) {
        validatePage(limit, offset);
        Map<String, Object> request = scope();
        request.put("limit", limit);
        request.put("offset", offset);
        addType(request, type);
        return data("查询 L1 记忆", client.queryAtomic(
                serviceId, userKey, authorization(), request));
    }

    public JsonNode search(String query, int limit, int offset, String type) {
        if (query == null || query.isBlank() || query.length() > 2048) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "搜索词需为 1 到 2048 字");
        }
        validatePage(limit, offset);
        String needle = query.strip().toLowerCase(Locale.ROOT);
        ArrayNode matches = mapper.createArrayNode();
        int matched = 0;
        // MemoryCore 的 search 是语义检索；关键词搜索需逐页读取并按正文精确包含匹配。
        for (int cursor = 0; ; cursor += 100) {
            JsonNode page = list(100, cursor, type);
            JsonNode items = page.path("items");
            if (!items.isArray()) break;
            for (JsonNode item : items) {
                if (!item.path("content").asText("").toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (matched >= offset && matched - offset < limit) matches.add(item);
                matched++;
            }
            if (items.size() < 100 || cursor + items.size() >= page.path("total").asInt()) break;
            if (cursor > Integer.MAX_VALUE - 100) break;
        }
        ObjectNode result = mapper.createObjectNode();
        result.set("items", matches);
        result.put("total", matched);
        return result;
    }

    public JsonNode find(String id) {
        validateId(id);
        // TencentDB 目前没有按 ID 获取单条原子记忆的接口；逐页查询后按 ID 精确定位。
        for (int offset = 0; ; offset += 100) {
            JsonNode page = list(100, offset, null);
            JsonNode items = page.path("items");
            if (items.isArray()) {
                for (JsonNode item : items) {
                    if (id.equals(item.path("id").asText())) return item;
                }
            }
            if (!items.isArray() || items.size() < 100 || offset + 100 >= page.path("total").asInt()
                    || offset > Integer.MAX_VALUE - 100) break;
        }
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "L1 记忆不存在");
    }

    public JsonNode update(String id, String content) {
        validateId(id);
        if (content == null || content.isBlank() || content.length() > 8192) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆正文需为 1 到 8192 字");
        }
        find(id);
        Map<String, Object> request = scope();
        request.put("id", id);
        request.put("content", content.strip());
        return data("更新 L1 记忆", client.updateAtomic(
                serviceId, userKey, authorization(), request));
    }

    public JsonNode delete(String id) {
        validateId(id);
        find(id);
        Map<String, Object> request = scope();
        request.put("ids", List.of(id));
        JsonNode result = data("删除 L1 记忆", client.deleteAtomic(
                serviceId, userKey, authorization(), request));
        if (result.path("deleted_count").asInt() != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "L1 记忆未删除");
        }
        return result;
    }

    private Map<String, Object> scope() {
        Map<String, Object> request = new HashMap<>();
        request.put("team_id", teamId);
        request.put("agent_id", agentId);
        request.put("user_id", userId);
        return request;
    }

    private void addType(Map<String, Object> request, String type) {
        if (type == null || type.isBlank()) return;
        if (!TYPES.contains(type)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的记忆类型");
        request.put("type", type);
    }

    private void validatePage(int limit, int offset) {
        if (limit < 1 || limit > 100 || offset < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "分页参数无效");
        }
    }

    private void validateId(String id) {
        if (id == null || id.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "记忆 ID 不能为空");
    }

    private String authorization() { return "Bearer " + bearerToken; }

    private <T> T data(String operation, MemoryResponse<T> response) {
        if (response == null || response.code() != 0 || response.data() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, operation + "失败");
        }
        return response.data();
    }
}
