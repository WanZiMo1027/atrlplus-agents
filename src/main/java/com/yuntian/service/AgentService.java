package com.yuntian.service;

import com.yuntian.dto.AgentChatRequest;
import com.yuntian.dto.AgentChatResponse;
import com.yuntian.dto.AgentDecision;
import com.yuntian.dto.ChatMessageItem;
import com.yuntian.memory.MemoryService;
import com.yuntian.memory.dto.AtomicSearchData;
import com.yuntian.memory.dto.MemoryResponse;
import dev.langchain4j.skills.Skills;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 聊天服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private final QQAssistant qqAssistant;

    private final MemoryService memoryService;

    private final Skills qqSkills;

    /**
     * 处理聊天请求
     * @param request
     * @return
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        // 构造本轮聊天消息
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("以下是QQ群中刚刚连续出现的消息：\n\n");

        for (ChatMessageItem message : request.messages()) {
            messageBuilder.append("[eventId=")
                    .append(message.eventId())
                    .append("]")
                    .append(message.senderName())
                    .append("：")
                    .append(message.text())
                    .append("\n");
        }
        String message = messageBuilder.toString();
        String conversationId = request.conversationId();
        log.info("请求消息：{}", message);
        //查询L1
        String longTermMemory ;
        try {
            MemoryResponse<AtomicSearchData> memoryResponse = memoryService.searchMemory(conversationId, message);
            longTermMemory = extractMemoryText(memoryResponse);
            log.info("L1 内存响应：{}", memoryResponse);
        }catch (Exception e){
            log.error("查询L1内存失败", e);
            longTermMemory = "查询相关长期记忆失败。";
        }

        // 仅注入 Skill 的名称和描述，由模型通过工具按需获取正文及参考文件。
        AgentDecision decision = qqAssistant.chat(conversationId, longTermMemory, qqSkills.formatAvailableSkills(), message);
        log.info("ai决策：shouldReply={}，replies={}，replyToEventId={}，meme={}",
                decision.shouldReply(), decision.replies(), decision.replyToEventId(), decision.meme());
        // 过滤回复，保留非空且非空格的回复，最多保留3个。
        List<String> replies = decision.replies() == null ?
                List.of():
                decision.replies().stream().filter(reply ->reply != null && !reply.isBlank())
                        .map(String::strip)
                        .limit(3)
                        .toList();

        // 过滤引用消息，保留非空且非空格的引用消息。
        String replyToEventId = validateReplyToEventId(decision.replyToEventId(), request.messages());

        if (replies.isEmpty()) {
            replyToEventId = null;
        }

        // 过滤表情，保留非空且非空格的表情。
        String meme = decision.meme();
        if (meme != null && meme.isBlank()){
            meme = null;
        }
        boolean hasContent = !replies.isEmpty() || meme != null;
        boolean shouldReply = decision.shouldReply() && hasContent;
        //保存本轮对话到L0
        try{
            String assistantMessage = shouldReply ? String.join("\n", replies) : "";
            memoryService.saveConversation(conversationId, message,assistantMessage);
            log.info("保存L0内存成功");
        }catch (Exception e){
            log.error("保存L0内存失败", e);
        }
        // shouldReply=false 时固定返回空文字和空表情，不向上游暴露无效内容。
        if (!shouldReply){
            log.info("AI 不回复");
            return new AgentChatResponse(false, List.of(), null, null);
        }
        // 原样透传文字与语义标签，包括仅发表情的空文字场景。
        log.info("AI 回复：reply={}，meme={}，replyToEventId={}", replies, meme, replyToEventId);
        return new AgentChatResponse(true, replies, replyToEventId, meme);
    }

    /**
     * 从 L1 内存响应中提取记忆文本
     * @param response L1 内存响应
     * @return 记忆文本字符串
     */
    private String extractMemoryText(MemoryResponse<AtomicSearchData> response) {
        if (response == null || response.data() == null || response.data().items() == null || response.data().items().isEmpty()) {
            return "没有相关长期记忆。";
        }
        StringBuilder builder = new StringBuilder();

        response.data().items().forEach(item -> {String content = item.path("content").asText();
            if (!content.isBlank()){
                builder.append("- ").append(content).append("\n");
            }
        });
        return builder.toString();
    }

    /**
     * 验证引用消息是否存在
     * @param candidate 引用消息 ID
     * @param messages 会话消息列表
     * @return 有效引用消息 ID 或 null
     */
    private String validateReplyToEventId(String candidate, List<ChatMessageItem> messages) {
        if (candidate == null || candidate.isBlank()) {
            return null;
        }

        boolean exists = messages.stream().anyMatch(message -> candidate.equals(message.eventId()));

        if (!exists) {
            log.warn("AI 返回了无效的 replyToEventId，已忽略: {}", candidate);
            return null;
        }
        return candidate;
    }
}
