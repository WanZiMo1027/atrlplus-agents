package com.yuntian.controller;

import com.yuntian.style.StyleLearningService;
import com.yuntian.style.StyleTypes.*;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** 只供服务间管理和检索验证，原始证据不允许浏览器缓存。 */
@RestController
@RequestMapping("/internal/admin/style-learning")
@RequiredArgsConstructor
@ConditionalOnProperty(name = {"agent.style-learning.enabled", "admin.internal.enabled"}, havingValue = "true")
public class StyleLearningAdminController {
    private final StyleLearningService service;
    @GetMapping("/catalog") public ResponseEntity<Catalog> catalog() { return ok(service.catalog()); }
    @PostMapping("/extractions") public ResponseEntity<Enqueued> extract(@RequestBody ExtractRequest body) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.enqueue(body.conversationId(), true));
    }
    @GetMapping("/status") public ResponseEntity<Status> status(@RequestParam String conversationId) { return ok(service.status(conversationId)); }
    @GetMapping("/fragments") public ResponseEntity<FragmentPage> fragments(@RequestParam String conversationId,
            @RequestParam(defaultValue = "0") long afterId, @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String version, @RequestParam(defaultValue = "") String query,
            @RequestParam(required = false) String intent, @RequestParam(required = false) String seriousness) {
        return ok(service.fragments(conversationId, afterId, limit, version, query, intent, seriousness));
    }
    @PostMapping("/search") public ResponseEntity<List<SearchHit>> search(@RequestBody SearchRequest body) { return ok(service.search(body)); }
    @PostMapping("/jobs/{id}/retry") public ResponseEntity<Enqueued> retry(@PathVariable UUID id) {
        return ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(service.retry(id));
    }
    private <T> ResponseEntity<T> ok(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
