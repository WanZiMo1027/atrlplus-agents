package com.yuntian.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.yuntian.memory.L1MemoryAdminService;
import com.yuntian.memory.MemoryPromptService;
import com.yuntian.memory.dto.PromptView;
import com.yuntian.memory.dto.PublishPromptRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

/** 仅供 chat-service 调用的 TencentDB 管理入口。 */
@RestController
@RequestMapping("/internal/admin/tencentdb/l1")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "admin.internal.enabled", havingValue = "true")
public class L1AdminController {
    private final L1MemoryAdminService memories;
    private final MemoryPromptService prompts;

    @GetMapping("/memories")
    public JsonNode list(@RequestParam(defaultValue = "20") int limit,
                         @RequestParam(defaultValue = "0") int offset,
                         @RequestParam(required = false) String type) {
        return memories.list(limit, offset, type);
    }

    @GetMapping("/memories/search")
    public JsonNode search(@RequestParam String query,
                           @RequestParam(defaultValue = "20") int limit,
                           @RequestParam(defaultValue = "0") int offset,
                           @RequestParam(required = false) String type) {
        return memories.search(query, limit, offset, type);
    }

    @GetMapping("/memories/{id}")
    public JsonNode get(@PathVariable String id) { return memories.find(id); }

    public record ContentRequest(String content) {}

    @PutMapping("/memories/{id}")
    public JsonNode update(@PathVariable String id, @RequestBody ContentRequest body) {
        return memories.update(id, body.content());
    }

    @DeleteMapping("/memories/{id}")
    public JsonNode delete(@PathVariable String id) { return memories.delete(id); }

    @GetMapping("/prompt")
    public PromptView prompt() { return prompts.effectivePrompt(); }

    @GetMapping(value = "/prompt/template", produces = MediaType.TEXT_PLAIN_VALUE)
    public String template() { return prompts.projectTemplate(); }

    @PostMapping("/prompt/publish")
    public PromptView publish(@RequestBody PublishPromptRequest request) {
        return prompts.publish(request);
    }
}
