package com.auteur.agent.tools;

import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.domain.CoverAsset;
import com.auteur.domain.CoverAssetRepository;
import com.auteur.domain.FactCheckIssue;
import com.auteur.domain.ImageAsset;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.insights.WeeklyReviewService;
import com.auteur.llm.ChatRequest;
import com.auteur.script.FactCheckFixService;
import com.auteur.script.ScriptAlignmentService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * P1 缺口工具集合(网页可点击但 agent 没暴露的高频写操作)。
 *
 *   - update_topic            (WRITE) PATCH topic 字段(白名单内,部分更新)
 *   - delete_topic            (WRITE) 选题硬删,有脚本时拒绝
 *   - delete_script           (WRITE) 脚本硬删(级联清 sections/shots/images/voice/video/cover)
 *   - align_script_timing     (WRITE) 重算 section/shot 时间戳对齐 voice 节奏(纯算,毫秒级)
 *   - dismiss_factcheck_issue (WRITE) 忽略 factcheck issue(标 resolved=true,不改文)
 *   - select_image_as_final   (WRITE) 选某 image_asset 作 shot 的 final
 *   - finalize_cover          (WRITE) 选某 cover 作 script 同 ratio 的 final(独占)
 *   - save_weekly_review      (WRITE) 保存周复盘(LLM 生成的 highlights/lessons/... 落库)
 *
 * 都是 WRITE risk → 走 HITL 审批,不直接生效。
 * Topic 白名单字段:title/projectName/dynasty/genre/protagonist/hookType/emotion/durationMinutes/
 *   potentialScore/historicalReference/status/notes/directorNote/presetId/seriesId。
 *   不开放:source / aiSuggestedSeries / 时间戳(系统管)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MoreOpsTools {

    private static final List<String> TOPIC_STATUSES =
            List.of("DRAFT", "SCHEDULED", "PRODUCED", "PUBLISHED", "ARCHIVED");

    private final ToolRegistry registry;
    private final TopicRepository topicRepo;
    private final ScriptRepository scriptRepo;
    private final ScriptAlignmentService alignmentService;
    private final FactCheckFixService factCheckFixService;
    private final ImageAssetRepository imageAssetRepo;
    private final CoverAssetRepository coverAssetRepo;
    private final WeeklyReviewService weeklyReviewService;
    private final PlatformTransactionManager txManager;

    /** 包多写操作:select_image_as_final / finalize_cover 都要原子地刷一组行,中途异常应回滚。 */
    private TransactionTemplate tx;

    @PostConstruct
    public void init() {
        this.tx = new TransactionTemplate(txManager);
        registry.register(new UpdateTopic());
        registry.register(new DeleteTopic());
        registry.register(new DeleteScript());
        registry.register(new AlignScriptTiming());
        registry.register(new DismissFactcheckIssue());
        registry.register(new SelectImageAsFinal());
        registry.register(new FinalizeCover());
        registry.register(new SaveWeeklyReview());
    }

    // ============ update_topic ============
    private class UpdateTopic implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "update_topic",
                    "改选题字段(部分更新,只改传入的字段)。" +
                            "status 用于工作流流转(DRAFT→SCHEDULED→PRODUCED→PUBLISHED 或 ARCHIVED)。",
                    Map.ofEntries(
                            Map.entry("type", "object"),
                            Map.entry("properties", Map.ofEntries(
                                    Map.entry("topicId", Map.of("type", "integer")),
                                    Map.entry("title", Map.of("type", "string")),
                                    Map.entry("projectName", Map.of("type", "string")),
                                    Map.entry("dynasty", Map.of("type", "string")),
                                    Map.entry("genre", Map.of("type", "string")),
                                    Map.entry("protagonist", Map.of("type", "string")),
                                    Map.entry("hookType", Map.of("type", "string")),
                                    Map.entry("emotion", Map.of("type", "string")),
                                    Map.entry("durationMinutes", Map.of("type", "integer")),
                                    Map.entry("potentialScore", Map.of("type", "number")),
                                    Map.entry("historicalReference", Map.of("type", "string")),
                                    Map.entry("seriesId", Map.of("type", "integer")),
                                    Map.entry("status", Map.of("type", "string", "enum", TOPIC_STATUSES)),
                                    Map.entry("notes", Map.of("type", "string")),
                                    Map.entry("directorNote", Map.of(
                                            "description", "导演笔记 — 跨角色共享的叙事/视觉方向。" +
                                                    "**字段是 MySQL JSON 列**,前端按结构化对象存储:tone/pacing/narrativeArc[A-E]/visualStyle/" +
                                                    "protagonistVibe/keyMoments/directorNotes。**强烈建议**传完整 JSON 对象。" +
                                                    "若只传纯字符串,工具会自动包装成 {directorNotes: \"<text>\"} 落库 — 前端能读但 narrativeArc 等关键段空着,下游 LLM 角色会失参考。"
                                    )),
                                    Map.entry("presetId", Map.of("type", "integer"))
                            )),
                            Map.entry("required", List.of("topicId"))
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("topicId").asLong();
            Topic t = topicRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "topic " + id + " 不存在"));
            if (args.hasNonNull("title")) t.setTitle(args.get("title").asText());
            if (args.hasNonNull("projectName")) t.setProjectName(args.get("projectName").asText());
            if (args.hasNonNull("dynasty")) t.setDynasty(args.get("dynasty").asText());
            if (args.hasNonNull("genre")) t.setGenre(args.get("genre").asText());
            if (args.hasNonNull("protagonist")) t.setProtagonist(args.get("protagonist").asText());
            if (args.hasNonNull("hookType")) t.setHookType(args.get("hookType").asText());
            if (args.hasNonNull("emotion")) t.setEmotion(args.get("emotion").asText());
            if (args.hasNonNull("durationMinutes")) t.setDurationMinutes(args.get("durationMinutes").asInt());
            if (args.hasNonNull("potentialScore")) {
                t.setPotentialScore(new BigDecimal(args.get("potentialScore").asText()));
            }
            if (args.hasNonNull("historicalReference")) t.setHistoricalReference(args.get("historicalReference").asText());
            if (args.hasNonNull("seriesId")) t.setSeriesId(args.get("seriesId").asLong());
            if (args.hasNonNull("status")) t.setStatus(TopicStatus.valueOf(args.get("status").asText()));
            if (args.hasNonNull("notes")) t.setNotes(args.get("notes").asText());
            if (args.hasNonNull("directorNote")) t.setDirectorNote(normalizeDirectorNote(args.get("directorNote")));
            if (args.hasNonNull("presetId")) t.setPresetId(args.get("presetId").asLong());
            Topic saved = topicRepo.save(t);
            log.info("[Agent] update_topic id={} status={}", id, saved.getStatus());

            // 只在本轮**真的**改了 directorNote 时检查"弱结构":如果用户只想改 title,我们不应该
            // 拿历史值打脸说 directorNote 不全 —— 那是上一轮的事。
            boolean directorNoteTouched = args.hasNonNull("directorNote");
            boolean directorNoteWeak = directorNoteTouched
                    && saved.getDirectorNote() != null
                    && !saved.getDirectorNote().isBlank()
                    && isWeakDirectorNote(saved.getDirectorNote());
            String hint = directorNoteWeak
                    ? "⚠️ directorNote 结构不全 — 你只填了 directorNotes 一字段(纯文本兜底包装),"
                            + "缺 tone/pacing/narrativeArc 等核心结构。**立即** read_skill('topic-creation') 看完整 JSON schema 重填。"
                    : "✓ ok";

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("id", saved.getId());
            out.put("status", saved.getStatus());
            out.put("title", saved.getTitle());
            out.put("directorNoteWeak", directorNoteWeak);
            out.put("hint", hint);
            return out;
        }
    }

    // ============ delete_topic ============
    private class DeleteTopic implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_topic",
                    "删除选题。注意:有脚本时会拒绝(409)— 用户得先删脚本或把选题改成 ARCHIVED 归档。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("topicId", Map.of("type", "integer")),
                            "required", List.of("topicId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("topicId").asLong();
            Topic t = topicRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "topic " + id + " 不存在"));
            long scriptCount = scriptRepo.countByTopicId(id);
            if (scriptCount > 0) {
                throw new ResponseStatusException(CONFLICT,
                        "选题已有 " + scriptCount + " 条脚本,删除会级联销毁;请先删脚本或将选题改成 ARCHIVED");
            }
            topicRepo.delete(t);
            log.info("[Agent] delete_topic id={} title={}", id, t.getTitle());
            return Map.of("ok", true, "id", id, "title", t.getTitle());
        }
    }

    // ============ delete_script ============
    private class DeleteScript implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_script",
                    "删除脚本(级联清理 sections/shots/images/voice/video/cover)。" +
                            "published_video / pipeline_run 是软引用,保留作历史。不可恢复 — 调用前向用户确认。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("scriptId", Map.of("type", "integer")),
                            "required", List.of("scriptId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            if (!scriptRepo.existsById(id)) {
                throw new ResponseStatusException(NOT_FOUND, "script " + id + " 不存在");
            }
            scriptRepo.deleteById(id);
            log.info("[Agent] delete_script id={}", id);
            return Map.of("ok", true, "id", id);
        }
    }

    // ============ align_script_timing ============
    private class AlignScriptTiming implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "align_script_timing",
                    "对齐画面 — 用户改完段落文案后刷一遍 section/shot 时间戳让画面切换跟朗读节奏对齐。" +
                            "纯算,毫秒级。有 voice+SRT 走真实对齐,否则按 4.5 字/秒估算。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("scriptId", Map.of("type", "integer")),
                            "required", List.of("scriptId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            ScriptAlignmentService.AlignmentResult r = alignmentService.align(id);
            log.info("[Agent] align_script_timing id={} mode={} sections={} shots={}",
                    id, r.mode(), r.sectionsUpdated(), r.shotsUpdated());
            return r;
        }
    }

    // ============ dismiss_factcheck_issue ============
    private class DismissFactcheckIssue implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "dismiss_factcheck_issue",
                    "忽略 factcheck issue(只标 resolved=true,不改文)。用于用户人工判断'这条提醒不需要修'的场景。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("issueId", Map.of("type", "integer")),
                            "required", List.of("issueId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("issueId").asLong();
            FactCheckIssue dismissed = factCheckFixService.dismiss(id);
            log.info("[Agent] dismiss_factcheck_issue id={}", id);
            return Map.of(
                    "ok", true,
                    "id", dismissed.getId(),
                    "scriptId", dismissed.getScriptId(),
                    "resolved", dismissed.getResolved()
            );
        }
    }

    // ============ select_image_as_final ============
    private class SelectImageAsFinal implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "select_image_as_final",
                    "把某 image_asset 选作所属 shot 的 final(同 shot 下其它 asset 自动 unset)。" +
                            "适合用户从多个候选图里挑一张用,不必走重生流程。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("assetId", Map.of("type", "integer")),
                            "required", List.of("assetId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long assetId = args.get("assetId").asLong();
            ImageAsset target = imageAssetRepo.findById(assetId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "image asset " + assetId + " 不存在"));
            if (target.getFileUrl() == null || target.getFileUrl().isBlank()) {
                throw new IllegalArgumentException("asset 没有 fileUrl(可能被审查拦截或还在生成中),不能选作 final");
            }
            // 同 shot 下其它 asset isFinal=false,目标 asset isFinal=true。整组改动包在事务里 ——
            // 中途异常就回滚,避免出现"该 shot 没有 final image"的半成品状态。
            int touched = tx.execute(status -> {
                List<ImageAsset> siblings = imageAssetRepo.findByShotIdOrderByIdAsc(target.getShotId());
                int n = 0;
                for (ImageAsset a : siblings) {
                    boolean shouldBeFinal = a.getId().equals(assetId);
                    if (Boolean.TRUE.equals(a.getIsFinal()) != shouldBeFinal) {
                        a.setIsFinal(shouldBeFinal);
                        imageAssetRepo.save(a);
                        n++;
                    }
                }
                return n;
            });
            log.info("[Agent] select_image_as_final assetId={} shotId={} touched={}",
                    assetId, target.getShotId(), touched);
            return Map.of("ok", true, "assetId", assetId, "shotId", target.getShotId(), "touched", touched);
        }
    }

    // ============ finalize_cover ============
    private class FinalizeCover implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "finalize_cover",
                    "把某封面标为该 script 同 ratio 下的 final(独占)。" +
                            "用户从多个封面候选里挑一张当封面用。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("coverId", Map.of("type", "integer")),
                            "required", List.of("coverId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("coverId").asLong();
            CoverAsset target = coverAssetRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "cover " + id + " 不存在"));
            // clearFinalExcept + save 必须在同一事务里:中途崩了不能让"同 ratio 没有 final cover"的状态被提交。
            CoverAsset saved = tx.execute(status -> {
                if (target.getRatio() != null) {
                    coverAssetRepo.clearFinalExcept(target.getScriptId(), target.getRatio(), id);
                }
                target.setIsFinal(true);
                return coverAssetRepo.save(target);
            });
            log.info("[Agent] finalize_cover id={} scriptId={} ratio={}",
                    id, target.getScriptId(), target.getRatio());
            return Map.of(
                    "ok", true,
                    "id", saved.getId(),
                    "scriptId", saved.getScriptId(),
                    "ratio", saved.getRatio()
            );
        }
    }

    // ============ save_weekly_review ============
    private class SaveWeeklyReview implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "save_weekly_review",
                    "保存周复盘到数据库(generate_weekly_review 生成的内容是临时的,要落库才能复用)。" +
                            "week 形如 '2024-W12'。upsert 语义,同 week 重复保存覆盖。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "week", Map.of("type", "string", "description", "ISO 周码,如 2024-W12"),
                                    "highlights", Map.of("type", "string"),
                                    "lessons", Map.of("type", "string"),
                                    "experiments", Map.of("type", "string"),
                                    "nextWeek", Map.of("type", "string", "description", "下周改进计划")
                            ),
                            "required", List.of("week")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String week = args.get("week").asText();
            WeeklyReviewService.WeeklyReviewSavePayload payload =
                    new WeeklyReviewService.WeeklyReviewSavePayload(
                            args.hasNonNull("highlights") ? args.get("highlights").asText() : null,
                            args.hasNonNull("lessons") ? args.get("lessons").asText() : null,
                            args.hasNonNull("experiments") ? args.get("experiments").asText() : null,
                            args.hasNonNull("nextWeek") ? args.get("nextWeek").asText() : null
                    );
            WeeklyReviewService.WeeklyReviewView saved = weeklyReviewService.upsertByWeek(week, payload);
            log.info("[Agent] save_weekly_review week={}", week);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("week", week);
            out.put("saved", saved);
            return out;
        }
    }

    /**
     * Topic.directorNote 是 MySQL JSON 列(前端 DirectorNoteDrawer 写的是结构化对象,
     * 含 tone/pacing/narrativeArc[A-E]/visualStyle/protagonistVibe/keyMoments/directorNotes)。
     * LLM 经常误以为是纯文本字段直接传字符串 → JSON 列拒绝 → SQL 报错。
     *
     * 本函数兼容三种输入:
     *   1. JsonNode 是对象/数组 → 直接序列化(LLM 传完整结构,前端可读)
     *   2. JsonNode 是字符串,且字符串本身是合法 JSON → 透传(LLM 已把 JSON stringify 一次)
     *   3. JsonNode 是普通字符串 → 包成 { "directorNotes": "<text>" }
     *      这样前端 normalizeNote() 会把它显示在"补充指令"段,不报错;其他段(narrativeArc 等)留空
     *      让用户/agent 之后慢慢补。
     */
    static String normalizeDirectorNote(JsonNode v) {
        if (v == null || v.isNull()) return null;
        // 已经是结构化(对象/数组)→ 直接转 JSON 文本
        if (v.isObject() || v.isArray()) return v.toString();
        // 是字符串:试试是不是 JSON 字符串
        String text = v.asText();
        if (text == null || text.isBlank()) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // 尝试当 JSON 解析,合法就透传
            try {
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(trimmed);
                return trimmed;
            } catch (Exception ignored) {
                // 看起来像 JSON 但解析失败,降级到文本包装
            }
        }
        // 纯文本 — 包成 directorNotes 字段
        return "{\"directorNotes\":" + jsonStringEscape(text) + "}";
    }

    /** JSON 字符串转义 — 不引入额外 ObjectMapper 序列化开销。 */
    private static String jsonStringEscape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /**
     * 检测 directorNote 是否"弱"——LLM 偷懒只塞了 directorNotes 一字段(走了 normalizeDirectorNote 兜底),
     * 缺核心结构字段(tone / pacing / narrativeArc 至少一个)。
     *
     * 弱的 directorNote 会让下游编剧 LLM 看不到分段叙事指导,各自漂移 — 必须当场打脸让 agent 重填。
     */
    static boolean isWeakDirectorNote(String json) {
        if (json == null || json.isBlank()) return true;
        try {
            com.fasterxml.jackson.databind.JsonNode node =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
            if (!node.isObject()) return true;
            // 至少要有 tone / pacing / narrativeArc 之一(narrativeArc 必须非空数组)
            boolean hasTone = node.hasNonNull("tone") && !node.get("tone").asText().isBlank();
            boolean hasPacing = node.hasNonNull("pacing") && !node.get("pacing").asText().isBlank();
            boolean hasArc = node.has("narrativeArc") && node.get("narrativeArc").isArray()
                    && node.get("narrativeArc").size() > 0;
            return !(hasTone || hasPacing || hasArc);
        } catch (Exception e) {
            return true;
        }
    }
}
