package com.yuntian.memory;

import com.yuntian.memory.client.TencentMemoryClient;
import com.yuntian.memory.dto.MemoryPromptCreateData;
import com.yuntian.memory.dto.MemoryPromptCreateRequest;
import com.yuntian.memory.dto.MemoryPromptListData;
import com.yuntian.memory.dto.MemoryPromptRecord;
import com.yuntian.memory.dto.MemoryPromptSetData;
import com.yuntian.memory.dto.MemoryPromptSetRequest;
import com.yuntian.memory.dto.MemoryResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MemoryPromptServiceTests {

    private static final String PROMPT = "第一行\r\n第二行\r\n";
    private static final String NORMALIZED_PROMPT = "第一行\n第二行";

    private TencentMemoryClient tencentMemoryClient;
    private MemoryPromptService memoryPromptService;

    @BeforeEach
    void setUp() throws Exception {
        tencentMemoryClient = mock(TencentMemoryClient.class);
        ResourceLoader resourceLoader = mock(ResourceLoader.class);
        Resource resource = mock(Resource.class);
        when(resourceLoader.getResource("classpath:prompts/tencent-l1-qq-group.txt")).thenReturn(resource);
        when(resource.exists()).thenReturn(true);
        when(resource.getInputStream()).thenAnswer(ignored -> new ByteArrayInputStream(
                PROMPT.getBytes(StandardCharsets.UTF_8)));

        memoryPromptService = new MemoryPromptService(tencentMemoryClient, resourceLoader);
        ReflectionTestUtils.setField(memoryPromptService, "serviceId", "default");
        ReflectionTestUtils.setField(memoryPromptService, "userKey", "user-key");
        ReflectionTestUtils.setField(memoryPromptService, "teamId", "team-1");
        ReflectionTestUtils.setField(memoryPromptService, "agentId", "agent-1");
        ReflectionTestUtils.setField(memoryPromptService, "bearerToken", "token");
    }

    @Test
    void createsAndAppliesPromptWhenNoExactMatchExists() {
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(ok(new MemoryPromptListData(List.of())));
        when(tencentMemoryClient.createMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptCreateData("mp-new", 1, 100L)));
        when(tencentMemoryClient.setMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptSetData(1)));

        var result = memoryPromptService.initializeL1Prompt();

        assertThat(result.memoryPromptId()).isEqualTo("mp-new");
        assertThat(result.created()).isTrue();
        assertThat(result.affected()).isEqualTo(1);
        assertThat(result.teamId()).isEqualTo("team-1");
        assertThat(result.agentId()).isEqualTo("agent-1");
        assertThat(result.layer()).isEqualTo("l1");

        ArgumentCaptor<MemoryPromptCreateRequest> createCaptor =
                ArgumentCaptor.forClass(MemoryPromptCreateRequest.class);
        verify(tencentMemoryClient).createMemoryPrompt(
                eq("default"), eq("user-key"), eq("Bearer token"), createCaptor.capture());
        assertThat(createCaptor.getValue()).isEqualTo(new MemoryPromptCreateRequest(
                "qq-group-chat-l1-memory", "l1", NORMALIZED_PROMPT));

        ArgumentCaptor<MemoryPromptSetRequest> setCaptor =
                ArgumentCaptor.forClass(MemoryPromptSetRequest.class);
        verify(tencentMemoryClient).setMemoryPrompt(
                eq("default"), eq("user-key"), eq("Bearer token"), setCaptor.capture());
        assertThat(setCaptor.getValue()).isEqualTo(new MemoryPromptSetRequest(
                "apply", "mp-new", "team-1", List.of("agent-1"), "l1"));
    }

    @Test
    void reusesExactPromptAndAcceptsIdempotentSetResponse() {
        MemoryPromptRecord existing = promptRecord("mp-existing", NORMALIZED_PROMPT);
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(ok(new MemoryPromptListData(List.of(existing))));
        when(tencentMemoryClient.setMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptSetData(0)));

        var result = memoryPromptService.initializeL1Prompt();

        assertThat(result.memoryPromptId()).isEqualTo("mp-existing");
        assertThat(result.created()).isFalse();
        assertThat(result.affected()).isZero();
        verify(tencentMemoryClient, never()).createMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void searchesSubsequentPagesBeforeCreating() {
        List<MemoryPromptRecord> firstPage = IntStream.range(0, 100)
                .mapToObj(index -> promptRecord("mp-other-" + index, "其他正文 " + index))
                .toList();
        when(tencentMemoryClient.getMemoryPrompts(
                "default", "user-key", "Bearer token", "l1", 100, 0, "desc"))
                .thenReturn(ok(new MemoryPromptListData(firstPage)));
        when(tencentMemoryClient.getMemoryPrompts(
                "default", "user-key", "Bearer token", "l1", 100, 100, "desc"))
                .thenReturn(ok(new MemoryPromptListData(List.of(
                        promptRecord("mp-page-two", NORMALIZED_PROMPT)))));
        when(tencentMemoryClient.setMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptSetData(1)));

        var result = memoryPromptService.initializeL1Prompt();

        assertThat(result.memoryPromptId()).isEqualTo("mp-page-two");
        assertThat(result.created()).isFalse();
        verify(tencentMemoryClient, never()).createMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void rejectsCreateResponseWithoutMemoryPromptId() {
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(ok(new MemoryPromptListData(List.of())));
        when(tencentMemoryClient.createMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptCreateData(" ", 1, 100L)));

        assertThatThrownBy(memoryPromptService::initializeL1Prompt)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("初始化 TencentDB L1 Memory Prompt 失败")
                .hasRootCauseMessage("创建 Memory Prompt 响应缺少 memory_prompt_id");
        verify(tencentMemoryClient, never()).setMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void stopsWhenCreateReturnsBusinessError() {
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(ok(new MemoryPromptListData(List.of())));
        when(tencentMemoryClient.createMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(new MemoryResponse<>(409, "prompt limit exceeded", "req-create", null));

        assertThatThrownBy(memoryPromptService::initializeL1Prompt)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("创建 Memory Prompt 失败：code=409 message=prompt limit exceeded requestId=req-create");
        verify(tencentMemoryClient, never()).setMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void stopsWhenQueryReturnsBusinessError() {
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(new MemoryResponse<>(503, "store unavailable", "req-query", null));

        assertThatThrownBy(memoryPromptService::initializeL1Prompt)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("查询 Memory Prompt 失败：code=503 message=store unavailable requestId=req-query");
        verify(tencentMemoryClient, never()).createMemoryPrompt(
                anyString(), anyString(), anyString(), any());
        verify(tencentMemoryClient, never()).setMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    @Test
    void retriesFailedSetByReusingPromptWithoutCreatingAgain() {
        MemoryPromptRecord existing = promptRecord("mp-existing", NORMALIZED_PROMPT);
        when(tencentMemoryClient.getMemoryPrompts(anyString(), anyString(), anyString(),
                anyString(), anyInt(), anyInt(), anyString()))
                .thenReturn(ok(new MemoryPromptListData(List.of())))
                .thenReturn(ok(new MemoryPromptListData(List.of(existing))));
        when(tencentMemoryClient.createMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(ok(new MemoryPromptCreateData("mp-existing", 1, 100L)));
        when(tencentMemoryClient.setMemoryPrompt(anyString(), anyString(), anyString(), any()))
                .thenReturn(new MemoryResponse<>(503, "store unavailable", "req-set", null))
                .thenReturn(ok(new MemoryPromptSetData(1)));

        assertThatThrownBy(memoryPromptService::initializeL1Prompt)
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("绑定 L1 Memory Prompt 失败：code=503 message=store unavailable requestId=req-set");

        var retryResult = memoryPromptService.initializeL1Prompt();

        assertThat(retryResult.memoryPromptId()).isEqualTo("mp-existing");
        assertThat(retryResult.created()).isFalse();
        verify(tencentMemoryClient, times(1)).createMemoryPrompt(
                anyString(), anyString(), anyString(), any());
        verify(tencentMemoryClient, times(2)).setMemoryPrompt(
                anyString(), anyString(), anyString(), any());
    }

    private static MemoryPromptRecord promptRecord(String id, String prompt) {
        return new MemoryPromptRecord(
                id, "qq-group-chat-l1-memory", "l1", prompt,
                1, "active", 100L, 100L);
    }

    private static <T> MemoryResponse<T> ok(T data) {
        return new MemoryResponse<>(0, "ok", "req-ok", data);
    }
}
