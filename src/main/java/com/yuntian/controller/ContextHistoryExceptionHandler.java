package com.yuntian.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

/** 查询失败不能显示为“暂无记录”；不向浏览器透传 Redis 内部异常或聊天资料。 */
@RestControllerAdvice(assignableTypes = ContextHistoryAdminController.class)
public class ContextHistoryExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> invalid() { return error(HttpStatus.BAD_REQUEST, "查询参数无效"); }
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> status(ResponseStatusException failure) {
        return error(failure.getStatusCode(), failure.getReason() == null ? "查询失败" : failure.getReason());
    }
    @ExceptionHandler({DataAccessException.class, io.lettuce.core.RedisException.class})
    public ResponseEntity<Map<String, String>> unavailable() { return error(HttpStatus.SERVICE_UNAVAILABLE, "Redis 暂时不可用，请稍后重试"); }
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, String>> corrupt() { return error(HttpStatus.INTERNAL_SERVER_ERROR, "聊天记录状态异常，无法读取"); }
    private ResponseEntity<Map<String, String>> error(HttpStatusCode status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
