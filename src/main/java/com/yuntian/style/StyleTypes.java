package com.yuntian.style;

import java.time.Instant;
import java.util.*;

/** 只保存输入证据，不接收或传播机器人回复计划。 */
public final class StyleTypes {
    private StyleTypes() {}
    public record Source(long sequence, String eventId, String senderId, String senderName,
                         Long timestamp, String text, String background, boolean human, boolean eligible) {}
    public record Window(long fromSequence, long throughSequence, int scanned, int effective,
                         List<Source> messages) {}
    public record Candidate(String scene, String intent, String emotion, String seriousness, String interaction,
                            List<String> contextEventIds, List<String> replyEventIds) {}
    public record Fragment(String fingerprint, String scene, String intent, String emotion, String seriousness,
                           String interaction, List<Source> context, List<Source> replies) {
        public String embeddingText() {
            return "场景：" + scene + "\n意图：" + intent + "\n情绪：" + emotion
                    + "\n认真程度：" + seriousness + "\n互动方式：" + interaction;
        }
    }
    public record Extraction(List<Fragment> fragments, int rejected, Integer inputTokens, Integer outputTokens) {}
    public record Job(UUID id, String conversationId, String kind, String status, long fromSequence,
                      long throughSequence, int attempts, UUID claimToken, Long fragmentId,
                      String payload, String profile, Instant leaseUntil) {}
    public record Enqueued(UUID jobId, String status) {}
    public record SearchRequest(String conversationId, String text, String intent, String seriousness, Integer limit) {}
    public record ExtractRequest(String conversationId) {}
    public record FragmentView(long id, String conversationId, long throughSequence, Fragment fragment,
                               String embeddingStatus) {}
    public record SearchHit(long id, Fragment fragment, double similarity) {}
    public record FragmentPage(List<FragmentView> items, Long nextAfterId, String version, long total) {}
    public record VersionOption(String version, long fragmentCount, boolean current) {}
    public record ConversationCatalog(String conversationId, String groupId, String selfId, List<VersionOption> versions) {}
    public record Catalog(String currentVersion, List<ConversationCatalog> conversations, List<String> intents,
                          List<String> seriousnessValues) {}
    public record Status(long cursor, long scanned, long effective, long fragments, long readyVectors,
                         long failedVectors, List<Map<String, Object>> jobs) {}
    public static final Set<String> INTENTS = Set.of("求助", "询问", "分享", "抱怨", "玩笑", "认可", "反对", "复读", "其他");
    public static final Set<String> SERIOUSNESS = Set.of("认真", "随意", "玩笑", "不明确");
}
