package com.yuntian.controller;

import com.yuntian.context.ContextManager;
import com.yuntian.context.ContextSummaryStore;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

/** 接收或提交失败不能伪装成成功接收的 shouldReply=false；客户端可用相同事件重试。 */
@RestControllerAdvice(assignableTypes = AgentController.class)
public class ContextExceptionHandler {
    @ExceptionHandler({ContextManager.ConversationBusyException.class, ContextSummaryStore.LeaseLostException.class,
            ContextSummaryStore.CapacityExceededException.class, ContextSummaryStore.CommitOutcomeUnknownException.class,
            DataAccessException.class})
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    public Map<String, String> unavailable(RuntimeException failure) {
        return Map.of("error", "CONTEXT_UNAVAILABLE", "message", "上下文未确认完成，请以相同事件重试");
    }

    @ExceptionHandler(ContextSummaryStore.StateConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public Map<String, String> conflict() { return Map.of("error", "CONTEXT_CONFLICT"); }
}
