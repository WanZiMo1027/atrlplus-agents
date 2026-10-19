package com.yuntian.service;

import com.yuntian.dto.*;
import com.yuntian.context.ContextManager;
import com.yuntian.context.ContextMessageFormatter;
import com.yuntian.context.ContextTokenEstimator;
import com.yuntian.context.DecisionChatModel.InvalidDecisionOutputException;
import com.yuntian.memory.MemoryService;
import com.yuntian.memory.dto.AtomicSearchData;
import com.yuntian.memory.dto.MemoryResponse;
import lombok.extern.slf4j.Slf4j;
import dev.langchain4j.service.output.OutputParsingException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.Optional;
import com.yuntian.style.StyleReplyService;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 聊天服务类
 */
@Service
@Slf4j
public class AgentService {

    private static final Set<String> ALLOWED_MEMES = Set.of(
            "哭脸", "流泪", "悲伤", "疑惑", "害怕", "装傻",
            "吃白饭", "开心", "友好", "惊喜", "喜欢", "同意"
    );

    private final QQAssistant qqAssistant;

    private final MemoryService memoryService;
    private final ContextManager contextManager;
    private final ContextMessageFormatter messageFormatter;
    private final Optional<StyleReplyService> styleReplies;

    @Autowired
    public AgentService(QQAssistant assistant,MemoryService memory,ContextManager context,ContextMessageFormatter formatter,Optional<StyleReplyService> styles) {
        this.qqAssistant=assistant;this.memoryService=memory;this.contextManager=context;this.messageFormatter=formatter;this.styleReplies=styles;
    }
    public AgentService(QQAssistant assistant,MemoryService memory,ContextManager context,ContextMessageFormatter formatter) {
        this(assistant,memory,context,formatter,Optional.empty());
    }

    /**
     * 处理聊天请求
     * @param request
     * @return
     */
    public AgentChatResponse chat(AgentChatRequest request) {
        // 主要流程：先在有效会话租约下持久化原文，再检索 L1 和调用模型；沉默或异常也不丢群友记录。
        try (var session = contextManager.open(request)) {
            if (session.replayedDecision() != null) {
                session.renew();
                // 重放只沿用已保存记录；关闭回复的请求不能返回以前的可发送结果。
                return request.replyEnabled() ? session.replayedDecision() : ContextManager.SILENT;
            }
            String conversationId = request.conversationId();
            String message = messageFormatter.text(request.messages());
            log.info("Agent 收到消息批次: conversationId={}, count={}", conversationId, request.messages().size());
            AgentChatResponse response = ContextManager.SILENT;
            // 主要流程：只记录模式保留原文和 L0 写入，跳过检索、摘要及回复模型。
            if (request.replyEnabled()) {
                String longTermMemory;
                session.renew();
                try {
                    longTermMemory = extractMemoryText(memoryService.searchMemory(conversationId, message));
                } catch (Exception failure) {
                    log.warn("查询 L1 失败，继续本轮决策: conversationId={}", conversationId);
                    longTermMemory = "没有可用的相关长期记忆。";
                }
                var context = session.prepare(longTermMemory);
                if (context.callable()) {
                    if(styleReplies.isPresent()) {
                        var styles=styleReplies.get();
                        try {
                            var reference=styles.references(conversationId,()->styles.input(conversationId,session.recentOriginalBatches(),request.messages(),session.sequence()));
                            context=session.attachStyleExamples(reference.examples());
                        }catch(RuntimeException unavailable) {
                            log.warn("风格参考不可用，继续基础决策: conversationId={}",conversationId);
                        }
                        // 可选依赖失败可以回退，租约失效则绝不能被上述降级吞掉。
                        session.renew();
                    }
                    try {
                        // 摘要经 @V 单独进入 system；当前多模态消息只由 LangChain4j 添加一次。
                        AgentDecision decision = qqAssistant.chat(conversationId, context.longTermMemory(),
                                context.summary(), context.styleExamples(), context.current());
                        response = normalize(decision, request.messages());
                    } catch (ContextTokenEstimator.BudgetExceededException failure) {
                        log.warn("最终请求超预算，保留原文并静默兜底: conversationId={}", conversationId);
                    } catch (InvalidDecisionOutputException failure) {
                        log.warn("主模型正文为空或未正常完成，保留原文并静默兜底: conversationId={}, {}",
                                conversationId, failure.getMessage());
                    } catch (OutputParsingException failure) {
                        // 解析异常可能含有未过滤模型正文，不打印异常内容；最终提交仍须通过租约和 revision 校验。
                        log.warn("主模型决策解析失败，保留原文并静默兜底: conversationId={}", conversationId);
                    }
                }
            }
            // 仅持久化过滤后的四字段协议；add(AiMessage) 收到的原始 JSON 绝不能作为实际发言。
            session.complete(response);
            session.renew();
            try {
                memoryService.saveConversation(conversationId, message,
                        response.shouldReply() ? String.join("\n", response.replies()) : "");
            } catch (Exception failure) {
                log.warn("保存 L0 失败，Redis 原文继续保留: conversationId={}", conversationId);
            }
            // 模型或 L0 调用超时、锁被接管后，旧任务不得继续返回可发送决策。
            session.renew();
            return response;
        }
    }

    private AgentChatResponse normalize(AgentDecision decision, List<ChatMessageItem> messages) {
        log.info("ai决策：shouldReply={}，replyCount={}",decision.shouldReply(),decision.replies()==null?0:decision.replies().size());

        // 过滤回复，保留非空且非空格的回复，最多保留3个。
        List<String> replies = decision.replies() == null ?
                List.of():
                decision.replies().stream().filter(reply ->reply != null && !reply.isBlank())
                        .map(String::strip)
                        .limit(3)
                        .toList();

        // 过滤引用消息，保留非空且非空格的引用消息。
        String replyToEventId = validateReplyToEventId(decision.replyToEventId(), messages);

        if (replies.isEmpty()) {
            replyToEventId = null;
        }

        // 表情只允许协议内的语义标签，避免模型偶发返回未知名称并透传给上游。
        String meme = decision.meme();
        if (meme != null) {
            meme = meme.strip();
            if (meme.isEmpty() || !ALLOWED_MEMES.contains(meme)) {
                log.warn("AI 返回了无效的 meme 标签，已忽略");
                meme = null;
            }
        }
        boolean hasContent = !replies.isEmpty() || meme != null;
        boolean shouldReply = decision.shouldReply() && hasContent;
        // shouldReply=false 时固定返回空文字和空表情，不向上游暴露无效内容。
        if (!shouldReply){
            log.info("AI 不回复");
            return new AgentChatResponse(false, List.of(), null, null);
        }
        // 原样透传文字与语义标签，包括仅发表情的空文字场景。
        log.info("AI 回复：replyCount={}，hasMeme={}，hasQuote={}", replies.size(), meme!=null, replyToEventId!=null);
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
            log.warn("AI 返回了无效的 replyToEventId，已忽略");
            return null;
        }
        return candidate;
    }
}
