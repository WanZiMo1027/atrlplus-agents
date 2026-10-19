package com.yuntian.style;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntian.context.ContextSummaryStore;
import com.yuntian.dto.ChatMessageItem;
import java.util.*;
import java.util.regex.*;
import static com.yuntian.style.StyleTypes.*;

/** 兼容旧网关描述；只在确定属于本人时提取措辞，引用和媒体始终只作背景。 */
public class StyleSourceReader {
    private static final Pattern SENDER = Pattern.compile("^发送者：(\"(?:[^\"\\\\]|\\\\.)*\")（QQ ([0-9]+)）$");
    private static final Pattern TEXT = Pattern.compile("^内容段 [0-9]+：文字 (.+)$");
    private static final Pattern TIME = Pattern.compile("^时间戳：([0-9]+)$");
    private final ObjectMapper json;
    private final StyleProperties properties;
    public StyleSourceReader(ObjectMapper json, StyleProperties properties) { this.json = json; this.properties = properties; }

    public Source normalize(String conversationId, long sequence, ChatMessageItem item) {
        String description = item.text() == null ? "" : item.text();
        String[] lines = description.split("\n", -1);
        String sender = item.senderId();
        String text = description;
        Long timestamp = null;
        boolean structured = lines.length >= 2 && lines[0].matches("消息ID：-?[0-9]+")
                && SENDER.matcher(lines[1]).matches();
        // 看似网关描述但格式不完整时，不把元数据误认成本人文字。
        if (description.startsWith("消息ID：") && !structured) text = "";
        if (structured) {
            Matcher header = SENDER.matcher(lines[1]); header.matches();
            if (sender == null || sender.isBlank()) sender = header.group(2);
            StringBuilder body = new StringBuilder();
            boolean inContent = false;
            for (int i = 2; i < lines.length; i++) {
                String line = lines[i];
                Matcher time = TIME.matcher(line);
                if (!inContent && time.matches()) {
                    try { timestamp = Long.valueOf(time.group(1)); } catch (NumberFormatException ignored) { /* 保留未知时间 */ }
                }
                if (line.startsWith("内容段 ") || line.startsWith("原始内容：")) inContent = true;
                // 旧格式没有可靠的引用结束标记，展开引用后不再采纳任何文字作本人措辞。
                if (line.startsWith("被引用消息发送者：") || line.startsWith("被引用原始内容：")) break;
                Matcher part = TEXT.matcher(line);
                String encoded = part.matches() ? part.group(1)
                        : line.startsWith("原始内容：") ? line.substring("原始内容：".length()) : null;
                if (encoded != null) {
                    try { body.append(json.readValue(encoded, String.class)); }
                    catch (Exception ignored) { /* 截断或无法解析的文字不得被模型补写 */ }
                }
            }
            text = body.toString();
        }
        String self = conversationId.split(":")[1];
        boolean human = sender != null && sender.matches("[0-9]+") && !sender.equals(self)
                && !properties.getBotSenderIds().contains(sender);
        return new Source(sequence, item.eventId(), sender, item.senderName(), timestamp, text,
                description, human, human && !text.isBlank());
    }

    public Window window(ContextSummaryStore.Snapshot snapshot, long cursor) {
        List<Source> overlap = new ArrayList<>(), fresh = new ArrayList<>();
        long through = cursor;
        int scanned = 0, effective = 0;
        for (var batch : snapshot.batches()) {
            if (batch.sequence() <= cursor) {
                batch.messages().forEach(m -> overlap.add(normalize(snapshot.conversationId(), batch.sequence(), m)));
            } else {
                if (!batch.completed()) break;
                if (!fresh.isEmpty() && fresh.size() + batch.messages().size() > properties.getMaxNewMessages()) break;
                var normalized = batch.messages().stream().map(m -> normalize(snapshot.conversationId(), batch.sequence(), m)).toList();
                fresh.addAll(normalized); through = batch.sequence(); scanned += normalized.size();
                effective += (int) normalized.stream().filter(Source::eligible).count();
            }
        }
        int start = Math.max(0, overlap.size() - properties.getOverlapMessages());
        List<Source> all = new ArrayList<>(overlap.subList(start, overlap.size())); all.addAll(fresh);
        return new Window(cursor, through, scanned, effective, List.copyOf(all));
    }
    public boolean thresholdReached(ContextSummaryStore.Snapshot snapshot, long cursor) {
        // 触发条件统计全部已完成积压，不能让窗口前面的图片挤掉后面的有效文字计数。
        return snapshot.batches().stream().filter(b -> b.sequence() > cursor)
                .takeWhile(ContextSummaryStore.Batch::completed)
                .flatMap(b -> b.messages().stream().map(m -> normalize(snapshot.conversationId(), b.sequence(), m)))
                .filter(Source::eligible).limit(properties.getMessageThreshold()).count() >= properties.getMessageThreshold();
    }
}
