package com.yuntian.context;

import com.yuntian.dto.AgentChatResponse;
import com.yuntian.dto.ChatMessageItem;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/** 管理页只读投影：仅返回聊天资料，不暴露 Redis key、租约 token 和幂等索引。 */
@Service
@RequiredArgsConstructor
public class ContextHistoryService {
    private final ContextSummaryStore store;

    public ConversationPage conversations(String cursor) {
        var page = store.scanForAdmin(cursor);
        return new ConversationPage(page.conversationIds().stream().map(id -> {
            String[] parts = id.split(":", -1);
            boolean qq = parts.length == 4 && parts[0].equals("qq")
                    && Set.of("group", "private").contains(parts[2]);
            return new Conversation(id, qq ? parts[2] : "other", qq ? parts[3] : id, qq ? parts[1] : null);
        }).toList(), page.nextCursor());
    }

    public MessagePage messages(String conversationId, Long beforeSequence, int limit, String query, String range) {
        if (conversationId == null || conversationId.isBlank() || conversationId.length() > 512
                || limit < 1 || limit > 100 || query == null || query.length() > 200
                || !Set.of("all", "active", "compressed").contains(range)
                || (beforeSequence != null && (beforeSequence <= 0 || beforeSequence > 9007199254740991L))) {
            throw new IllegalArgumentException("会话、分页或筛选参数无效");
        }
        var snapshot = store.readForAdmin(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
        String keyword = query.strip().toLowerCase(Locale.ROOT);
        var matching = snapshot.batches().stream()
                .filter(batch -> range.equals("all") || (range.equals("compressed")
                        == (batch.sequence() <= snapshot.lastCompressedSequence())))
                .filter(batch -> matches(batch, keyword)).toList();
        var eligible = matching.stream().filter(batch -> beforeSequence == null || batch.sequence() < beforeSequence).toList();
        int start = Math.max(0, eligible.size() - limit);
        // 存储已按数字排序；从尾部取最近若干完整批次，响应仍按正序展示。
        var batches = eligible.subList(start, eligible.size()).stream().map(batch ->
                new HistoryBatch(batch.sequence(), batch.messages(), batch.decision(), batch.completed(),
                        batch.sequence() <= snapshot.lastCompressedSequence())).toList();
        Long nextBefore = start > 0 ? batches.get(0).sequence() : null;
        return new MessagePage(conversationId, snapshot.revision(), snapshot.nextSequence(), snapshot.summary(),
                snapshot.summaryVersion(), snapshot.lastCompressedSequence(), matching.size(), batches, nextBefore);
    }

    private boolean matches(ContextSummaryStore.Batch batch, String keyword) {
        if (keyword.isEmpty()) return true;
        for (var message : batch.messages()) {
            if (contains(message.eventId(), keyword) || contains(message.senderName(), keyword)
                    || contains(message.senderId(), keyword) || contains(message.text(), keyword)
                    || message.images().stream().anyMatch(image -> contains(image.summary(), keyword) || contains(image.file(), keyword))) return true;
        }
        var decision = batch.decision();
        return decision != null && (decision.replies().stream().anyMatch(reply -> contains(reply, keyword))
                || contains(decision.replyToEventId(), keyword) || contains(decision.meme(), keyword));
    }

    private boolean contains(String value, String keyword) { return value != null && value.toLowerCase(Locale.ROOT).contains(keyword); }

    public record Conversation(String conversationId, String type, String targetId, String selfId) {}
    public record ConversationPage(List<Conversation> items, String nextCursor) {}
    public record HistoryBatch(long sequence, List<ChatMessageItem> messages, AgentChatResponse decision,
                               boolean completed, boolean compressed) {}
    public record MessagePage(String conversationId, long revision, long nextSequence, String summary,
                              long summaryVersion, long lastCompressedSequence, int matchingBatches,
                              List<HistoryBatch> batches, Long nextBeforeSequence) {}
}
