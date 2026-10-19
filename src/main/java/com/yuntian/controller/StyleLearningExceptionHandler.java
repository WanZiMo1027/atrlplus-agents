package com.yuntian.controller;

import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestControllerAdvice(assignableTypes = StyleLearningAdminController.class)
public class StyleLearningExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<?> invalid() { return error(HttpStatus.BAD_REQUEST, "学习参数无效"); }
    @ExceptionHandler(NoSuchElementException.class) public ResponseEntity<?> missing() { return error(HttpStatus.NOT_FOUND, "记录不存在"); }
    @ExceptionHandler({DataAccessException.class, IllegalStateException.class}) public ResponseEntity<?> unavailable() {
        return error(HttpStatus.SERVICE_UNAVAILABLE, "学习或检索暂不可用，请检查内部任务状态");
    }
    private ResponseEntity<?> error(HttpStatus status, String message) {
        return ResponseEntity.status(status).cacheControl(CacheControl.noStore()).body(Map.of("message", message));
    }
}
