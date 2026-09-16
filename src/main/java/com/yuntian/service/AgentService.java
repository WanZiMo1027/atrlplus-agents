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
            messageBuilder.append(message.senderName())
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
        AgentDecision decision = qqAssistant.chat(
                conversationId, longTermMemory, qqSkills.formatAvailableSkills(), message);
        log.info("ai决策：shouldreply={}，reply={}", decision.shouldReply(), decision.reply());
        //保存本轮对话到L0
        try{
            String assistanMessage = "";
            if (decision.shouldReply() && !decision.reply().isBlank()){
                assistanMessage = decision.reply();
            }
            memoryService.saveConversation(conversationId, message,assistanMessage);
            log.info("保存L0内存成功");
        }catch (Exception e){
            log.error("保存L0内存失败", e);
        }
        //根据ai决策是否回复，判断是否返回回复
        if (!decision.shouldReply()){
            log.info("AI 不回复");
            return new AgentChatResponse(false, "");
        }
        // 如果回复不为空，返回回复
        log.info("AI 回复：{}", decision.reply());
        return new AgentChatResponse(true, decision.reply());
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
}
