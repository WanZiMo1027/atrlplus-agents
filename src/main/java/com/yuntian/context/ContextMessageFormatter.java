package com.yuntian.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.yuntian.dto.ChatMessageItem;
import dev.langchain4j.data.message.*;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 当前输入保留图片，历史只带原始文字及媒体元数据，不重复下载历史图片。 */
@Component
public class ContextMessageFormatter {
    private final ObjectMapper json;
    public ContextMessageFormatter(ObjectMapper json) { this.json = json; }

    public String text(List<ChatMessageItem> messages) {
        StringBuilder out = new StringBuilder("以下是QQ群中按顺序出现的消息：\n");
        for (ChatMessageItem item : messages) {
            out.append("[eventId=").append(quoted(item.eventId())).append("] 发送者=")
                    .append(quoted(item.senderName())).append(" userId=")
                    .append(item.senderId() == null || item.senderId().isBlank()
                            ? "未知（不得仅按昵称合并身份，事件=" + quoted(item.eventId()) + "）" : quoted(item.senderId()))
                    .append("\n<message_description>\n").append(item.text())
                    .append("\n</message_description>\n");
            if (!item.images().isEmpty()) out.append("媒体元数据（不代表看过图片）：").append(quoted(item.images())).append('\n');
        }
        return out.toString();
    }

    public List<Content> current(List<ChatMessageItem> messages) {
        List<Content> contents = new ArrayList<>();
        for (ChatMessageItem item : messages) {
            contents.add(TextContent.from(text(List.of(item))));
            item.images().forEach(image -> {
                contents.add(TextContent.from("这条消息附带的图片仅供本轮理解："));
                contents.add(ImageContent.from(image.url()));
            });
        }
        return List.copyOf(contents);
    }

    public String history(ContextSummaryStore.Batch batch) {
        String out = "[历史批次=" + batch.sequence() + "]\n" + text(batch.messages());
        // 业务决策不等于网关送达证明；沉默、未完成批次不能虚构任何机器人发言。
        if (batch.decision() != null && batch.decision().shouldReply()) {
            out += "\n<bot_plan delivery=\"unknown\">以下仅为已过滤的回复计划，发送状态未知，不能当成已发生的群聊事实：\n"
                    + quoted(batch.decision()) + "\n</bot_plan>\n";
        }
        return out;
    }

    private String quoted(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException failure) { throw new IllegalArgumentException("消息格式化失败", failure); }
    }
}
