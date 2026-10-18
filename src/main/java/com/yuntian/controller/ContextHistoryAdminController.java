package com.yuntian.controller;

import com.yuntian.context.ContextHistoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** 仅供已鉴权的 chat-service 管理代理调用，禁止缓存私人聊天资料。 */
@RestController
@RequestMapping("/internal/admin/chat-history")
@RequiredArgsConstructor
@ConditionalOnProperty(name = "admin.internal.enabled", havingValue = "true")
public class ContextHistoryAdminController {
    private final ContextHistoryService history;

    @GetMapping("/conversations")
    public ResponseEntity<ContextHistoryService.ConversationPage> conversations(@RequestParam(defaultValue = "0") String cursor) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(history.conversations(cursor));
    }

    @GetMapping("/messages")
    public ResponseEntity<ContextHistoryService.MessagePage> messages(@RequestParam String conversationId,
            @RequestParam(required = false) Long beforeSequence, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "") String query, @RequestParam(defaultValue = "all") String range) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(history.messages(conversationId, beforeSequence, limit, query, range));
    }
}
