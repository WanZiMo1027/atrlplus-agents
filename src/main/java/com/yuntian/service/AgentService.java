package com.yuntian.service;

import com.yuntian.dto.*;
import com.yuntian.memory.MemoryService;
import com.yuntian.memory.dto.AtomicSearchData;
import com.yuntian.memory.dto.MemoryResponse;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 聊天服务类
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentService {

    private static final Set<String> ALLOWED_MEMES = Set.of(
            "哭脸", "流泪", "悲伤", "疑惑", "害怕", "阿巴阿巴", "装傻",
            "吃白饭", "开心", "友好", "惊喜", "喜欢", "同意"
    );

    private final QQAssistant qqAssistant;

    private final MemoryService memoryService;

    /**
     * 处理聊天请求
     * @param request
     * @return
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        // 主要流程：逐条界定消息边界，chat-service 的消息描述不能伪装为下一条事件。
        StringBuilder messageBuilder = new StringBuilder();
        messageBuilder.append("以下是QQ群中刚刚连续出现的消息：\n\n");

        for (ChatMessageItem message : request.messages()) {
            messageBuilder.append("[eventId=")
                    .append(message.eventId())
                    .append("] 发送者=")
                    .append(message.senderName())
                    .append("\n<message_description>\n")
                    .append(message.text())
                    .append("\n</message_description>\n");
        }
        String message = messageBuilder.toString();
        String conversationId = request.conversationId();
        log.info("Agent 收到消息批次: conversationId={}, count={}", conversationId, request.messages().size());
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

        List<Content> contents = new ArrayList<>();
        contents.add(TextContent.from("以下是QQ群中刚刚连续出现的消息：\n\n"));

        for (ChatMessageItem item : request.messages()) {

            String text = "[eventId=" + item.eventId() + "] 发送者="
                    + item.senderName()
                    + "\n<message_description>\n"
                    + item.text()
                    + "\n</message_description>\n";
            // 先添加这条消息的文字
            contents.add(TextContent.from(text));
            // 如果这条消息有图片，再紧跟着添加图片
            for (ChatImageItem image : item.images()) {
                contents.add(TextContent.from("以下是这条消息附带的动画表情原图,仅供本轮理解："));
                contents.add(ImageContent.from(image.url()));
            }
        }
        // 整批消息组装完之后，只调用一次模型
        AgentDecision decision = qqAssistant.chat(
                conversationId,
                longTermMemory,
                contents
        );
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

        // 表情只允许协议内的语义标签，避免模型偶发返回未知名称并透传给上游。
        String meme = decision.meme();
        if (meme != null) {
            meme = meme.strip();
            if (meme.isEmpty() || !ALLOWED_MEMES.contains(meme)) {
                log.warn("AI 返回了无效的 meme 标签，已忽略: {}", meme);
                meme = null;
            }
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
