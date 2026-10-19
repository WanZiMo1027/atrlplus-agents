package com.yuntian.style;

import com.fasterxml.jackson.databind.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.output.FinishReason;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static com.yuntian.style.StyleTypes.*;

/** 独立提取器只产生事件选择，原话由程序恢复；严格校验后才允许提交进度。 */
public class StyleExtractor {
    private final ChatModel model;
    private final ObjectMapper json;
    private final StyleProperties properties;
    private final String rules;
    private final String reviewRules;
    public StyleExtractor(ChatModel model, ObjectMapper json, StyleProperties properties, String rules) {
        this.model = model; this.json = json; this.properties = properties; this.rules = rules;
        try (var input = new org.springframework.core.io.ClassPathResource("prompts/style-review.txt").getInputStream()) {
            this.reviewRules = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new IllegalStateException("STYLE_REVIEW_RESOURCE_MISSING"); }
    }
    public String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception e) { throw new IllegalArgumentException("STYLE_SERIALIZATION_FAILED"); }
    }
    public boolean fits(Window window) {
        // 与主上下文一致按 UTF-8 字节保守估算，预留角色封装、协议和模型输出。
        return (long) rules.getBytes(StandardCharsets.UTF_8).length + encode(window).getBytes(StandardCharsets.UTF_8).length
                + properties.getOutputTokens() + 512 <= properties.getInputBudget();
    }
    public Window fit(Window original) {
        Window window = original;
        // 预算紧张时先退出最早的重叠背景，再按完整批次缩小新增窗口。
        while (!fits(window) && !window.messages().isEmpty() && window.messages().get(0).sequence() <= original.fromSequence()) {
            window = new Window(window.fromSequence(), window.throughSequence(), window.scanned(), window.effective(),
                    List.copyOf(window.messages().subList(1, window.messages().size())));
        }
        while (!fits(window)) {
            long last = window.throughSequence();
            var remaining = window.messages().stream().filter(m -> m.sequence() < last).toList();
            long through = remaining.stream().filter(m -> m.sequence() > original.fromSequence())
                    .mapToLong(Source::sequence).max().orElse(original.fromSequence());
            if (through <= original.fromSequence()) throw new IllegalStateException("STYLE_BATCH_OVER_BUDGET");
            var fresh = remaining.stream().filter(m -> m.sequence() > original.fromSequence()).toList();
            window = new Window(original.fromSequence(), through, fresh.size(),
                    (int) fresh.stream().filter(Source::eligible).count(), remaining);
        }
        return window;
    }

    public Extraction extract(Window window) {
        return extract(window, () -> {});
    }
    public Extraction extract(Window window, Runnable guard) {
        if (!fits(window)) throw new IllegalStateException("STYLE_BATCH_OVER_BUDGET");
        guard.run();
        var response = model.chat(ChatRequest.builder().messages(SystemMessage.from(rules), UserMessage.from(encode(window)))
                .parameters(ChatRequestParameters.builder().maxOutputTokens(properties.getOutputTokens()).build()).build());
        guard.run();
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().text().isBlank() || response.aiMessage().hasToolExecutionRequests()
                || response.metadata() == null || response.metadata().finishReason() != FinishReason.STOP) {
            throw new IllegalStateException("STYLE_INVALID_MODEL_COMPLETION");
        }
        var usage = response.metadata().tokenUsage();
        if (usage != null && usage.outputTokenCount() != null && usage.outputTokenCount() > properties.getOutputTokens()) {
            throw new IllegalStateException("STYLE_OUTPUT_OVER_BUDGET");
        }
        JsonNode root;
        try { root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.aiMessage().text()); }
        catch (Exception e) { throw new IllegalStateException("STYLE_INVALID_MODEL_JSON"); }
        if (!root.isObject() || root.size() != 1 || !root.path("fragments").isArray() || root.path("fragments").size() > 12) {
            throw new IllegalStateException("STYLE_INVALID_MODEL_JSON");
        }
        List<Fragment> fragments = new ArrayList<>(); int rejected = 0, invalidCandidates = 0;
        for (var node : root.path("fragments")) {
            try {
                var candidate = json.readerFor(Candidate.class).with(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                        .readValue(node);
                if (overlapOnly((Candidate) candidate, window)) { rejected++; continue; }
                fragments.add(validate((Candidate) candidate, window));
            } catch (Exception invalid) { rejected++; invalidCandidates++; }
        }
        // 全部候选均非法并非“没有互动”，应保留失败窗口供排查和重试。
        if (invalidCandidates > 0 && fragments.isEmpty()) throw new IllegalStateException("STYLE_ALL_CANDIDATES_INVALID");
        Integer input = usage == null ? null : usage.inputTokenCount();
        Integer output = usage == null ? null : usage.outputTokenCount();
        List<Fragment> accepted = new ArrayList<>();
        List<Fragment> chunk = new ArrayList<>();
        // 按窗口复核真实的回应关系，不把来源合法误当作语义正确；复核也独立限额。
        for (Fragment fragment : fragments) {
            chunk.add(fragment);
            if (!reviewFits(chunk)) {
                chunk.remove(chunk.size() - 1);
                if (chunk.isEmpty()) throw new IllegalStateException("STYLE_REVIEW_OVER_BUDGET");
                Extraction review = review(chunk, guard); accepted.addAll(review.fragments());
                input = sum(input, review.inputTokens()); output = sum(output, review.outputTokens());
                chunk = new ArrayList<>(List.of(fragment));
            }
        }
        if (!chunk.isEmpty()) {
            Extraction review = review(chunk, guard); accepted.addAll(review.fragments());
            input = sum(input, review.inputTokens()); output = sum(output, review.outputTokens());
        }
        return new Extraction(accepted, rejected + fragments.size() - accepted.size(), input, output);
    }
    private boolean overlapOnly(Candidate candidate, Window window) {
        if (candidate == null || candidate.contextEventIds() == null || candidate.replyEventIds() == null
                || candidate.contextEventIds().isEmpty() || candidate.replyEventIds().isEmpty()) return false;
        Map<String, Source> sources = new HashMap<>(); window.messages().forEach(s -> sources.put(s.eventId(), s));
        return java.util.stream.Stream.concat(candidate.contextEventIds().stream(), candidate.replyEventIds().stream())
                .allMatch(id -> sources.containsKey(id) && sources.get(id).sequence() <= window.fromSequence());
    }

    public Fragment validate(Candidate c, Window window) {
        if (c == null || !bounded(c.scene(), 600) || !bounded(c.emotion(), 40) || !bounded(c.interaction(), 400)
                || !INTENTS.contains(c.intent()) || !SERIOUSNESS.contains(c.seriousness())
                || c.contextEventIds() == null || c.replyEventIds() == null || c.contextEventIds().isEmpty()
                || c.replyEventIds().isEmpty() || c.contextEventIds().size() + c.replyEventIds().size() > 20) invalid();
        Map<String, Source> sources = new HashMap<>(); Map<String, Integer> order = new HashMap<>();
        for (int i = 0; i < window.messages().size(); i++) {
            Source source = window.messages().get(i); sources.put(source.eventId(), source); order.put(source.eventId(), i);
        }
        List<String> all = new ArrayList<>(c.contextEventIds()); all.addAll(c.replyEventIds());
        int previous = -1; Set<String> senders = new HashSet<>(); boolean fresh = false;
        for (String id : all) {
            Source source = sources.get(id); Integer position = order.get(id);
            if (source == null || position <= previous || !source.human() || source.senderId() == null
                    || !source.senderId().matches("[0-9]+") || properties.getBotSenderIds().contains(source.senderId())
                    || id.startsWith(source.senderId() + ":")) invalid();
            previous = position; senders.add(source.senderId()); fresh |= source.sequence() > window.fromSequence();
        }
        if (senders.size() < 2 || !fresh) invalid();
        var contexts = c.contextEventIds().stream().map(sources::get).toList();
        var replies = c.replyEventIds().stream().map(sources::get).toList();
        if (replies.stream().map(Source::senderId).distinct().count() != 1
                || replies.stream().anyMatch(s -> !s.eligible() || addressedToBot(s))
                || contexts.stream().noneMatch(s -> replies.stream().anyMatch(r -> !r.senderId().equals(s.senderId())))) invalid();
        return new Fragment(hash(encode(List.of(c.contextEventIds(), c.replyEventIds()))), c.scene(), c.intent(),
                c.emotion(), c.seriousness(), c.interaction(), contexts, replies);
    }
    private boolean addressedToBot(Source source) {
        String text = source.text().stripLeading().replaceFirst("^@", "");
        if (properties.getBotAddressNames().stream().anyMatch(text::startsWith)) return true;
        int separator = source.eventId().indexOf(':');
        if (separator < 0) return false;
        String self = source.eventId().substring(0, separator);
        String unquoted = source.background().split("被引用消息发送者：|被引用原始内容：", 2)[0];
        return java.util.regex.Pattern.compile("^内容段 [0-9]+：[^\\n]*:" + java.util.regex.Pattern.quote(self) + "$", java.util.regex.Pattern.MULTILINE)
                .matcher(unquoted).find();
    }
    private Integer sum(Integer left, Integer right) { return left == null || right == null ? null : left + right; }
    private List<Map<String, Object>> reviewInput(List<Fragment> fragments) {
        return fragments.stream().map(f -> Map.<String, Object>of("fingerprint", f.fingerprint(), "scene", f.scene(),
                "intent", f.intent(), "seriousness", f.seriousness(), "context", f.context().stream().map(this::reviewSource).toList(),
                "replies", f.replies().stream().map(this::reviewSource).toList())).toList();
    }
    private Map<String, Object> reviewSource(Source source) {
        String background = source.text().isBlank() ? source.background().substring(0, Math.min(1000, source.background().length())) : "";
        return Map.of("senderId", source.senderId(), "text", source.text(), "mediaContext", background,
                "directedToBot", addressedToBot(source));
    }
    private boolean reviewFits(List<Fragment> fragments) {
        return (long) encode(reviewInput(fragments)).getBytes(StandardCharsets.UTF_8).length
                + reviewRules.getBytes(StandardCharsets.UTF_8).length + Math.min(1024, properties.getOutputTokens()) + 512 <= properties.getInputBudget();
    }
    private Extraction review(List<Fragment> fragments, Runnable guard) {
        if (!reviewFits(fragments)) throw new IllegalStateException("STYLE_REVIEW_OVER_BUDGET");
        guard.run();
        var response = model.chat(ChatRequest.builder().messages(SystemMessage.from(reviewRules), UserMessage.from(encode(reviewInput(fragments))))
                .parameters(ChatRequestParameters.builder().maxOutputTokens(Math.min(1024, properties.getOutputTokens())).build()).build());
        guard.run();
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null
                || response.aiMessage().hasToolExecutionRequests() || response.metadata() == null || response.metadata().finishReason() != FinishReason.STOP) {
            throw new IllegalStateException("STYLE_INVALID_REVIEW_COMPLETION");
        }
        JsonNode root;
        try { root = json.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(response.aiMessage().text()); }
        catch (Exception e) { throw new IllegalStateException("STYLE_INVALID_REVIEW_JSON"); }
        if (!root.isObject() || root.size() != 1 || !root.path("accepted").isArray()) throw new IllegalStateException("STYLE_INVALID_REVIEW_JSON");
        Set<String> known = new HashSet<>(); fragments.forEach(f -> known.add(f.fingerprint()));
        Set<String> selected = new HashSet<>();
        for (var id : root.path("accepted")) {
            if (!id.isTextual() || !known.contains(id.asText()) || !selected.add(id.asText())) throw new IllegalStateException("STYLE_INVALID_REVIEW_SELECTION");
        }
        var usage = response.metadata().tokenUsage();
        if (usage != null && usage.outputTokenCount() != null && usage.outputTokenCount() > Math.min(1024, properties.getOutputTokens())) {
            throw new IllegalStateException("STYLE_REVIEW_OUTPUT_OVER_BUDGET");
        }
        return new Extraction(fragments.stream().filter(f -> selected.contains(f.fingerprint())).toList(), fragments.size() - selected.size(),
                usage == null ? null : usage.inputTokenCount(), usage == null ? null : usage.outputTokenCount());
    }
    private boolean bounded(String value, int max) { return value != null && !value.isBlank() && value.length() <= max; }
    private void invalid() { throw new IllegalArgumentException("STYLE_INVALID_CANDIDATE"); }
    public static String hash(String input) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("STYLE_HASH_FAILED"); }
    }
}
