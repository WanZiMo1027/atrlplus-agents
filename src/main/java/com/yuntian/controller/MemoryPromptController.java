package com.yuntian.controller;

import com.yuntian.memory.MemoryPromptService;
import com.yuntian.memory.dto.MemoryPromptInitResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * TencentDB Memory Prompt 管理入口，仅应在可信内网调用。
 */
@RestController
@RequestMapping("/internal/memory/l1-prompt")
@RequiredArgsConstructor
public class MemoryPromptController {

    private final MemoryPromptService memoryPromptService;

    @PostMapping("/init")
    public MemoryPromptInitResponse initialize() {
        return memoryPromptService.initializeL1Prompt();
    }
}
