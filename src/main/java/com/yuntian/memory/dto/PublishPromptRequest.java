package com.yuntian.memory.dto;

public record PublishPromptRequest(String content, String expectedMemoryPromptId, int expectedVersion) {
}
