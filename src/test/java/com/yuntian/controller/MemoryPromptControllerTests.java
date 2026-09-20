package com.yuntian.controller;

import com.yuntian.memory.MemoryPromptService;
import com.yuntian.memory.dto.MemoryPromptInitResponse;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MemoryPromptControllerTests {

    @Test
    void exposesManualInitializationEndpoint() throws Exception {
        MemoryPromptService service = mock(MemoryPromptService.class);
        when(service.initializeL1Prompt()).thenReturn(new MemoryPromptInitResponse(
                "mp-new", true, 1, "team-1", "agent-1", "l1"));
        var mockMvc = MockMvcBuilders.standaloneSetup(new MemoryPromptController(service)).build();

        mockMvc.perform(post("/internal/memory/l1-prompt/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memoryPromptId").value("mp-new"))
                .andExpect(jsonPath("$.created").value(true))
                .andExpect(jsonPath("$.affected").value(1))
                .andExpect(jsonPath("$.teamId").value("team-1"))
                .andExpect(jsonPath("$.agentId").value("agent-1"))
                .andExpect(jsonPath("$.layer").value("l1"));
        verify(service).initializeL1Prompt();
    }
}
