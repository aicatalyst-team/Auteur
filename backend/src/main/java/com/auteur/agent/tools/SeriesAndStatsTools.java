package com.auteur.agent.tools;

import com.auteur.agent.ActionToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.common.text.TextUtils;
import com.auteur.domain.GenreStatSnapshot;
import com.auteur.domain.GenreStatSnapshotRepository;
import com.auteur.domain.Series;
import com.auteur.domain.SeriesHook;
import com.auteur.domain.SeriesHookRepository;
import com.auteur.domain.SeriesRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.insights.InsightService;
import com.auteur.insights.VideoAttributionService;
import com.auteur.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * P2 - 系列 / 体裁基准 / Insight 工具集:
 *
 *   - dismiss_series_hook       (WRITE) 软删 hook(设 dismissed_at)
 *   - undismiss_series_hook     (WRITE) 撤销忽略
 *   - fulfill_hook_with_new_topic (WRITE) hook → 新 Topic + 回填 toTopicId
 *   - create_series             (WRITE)
 *   - delete_series             (WRITE) 系列下还有选题时拒绝
 *   - create_genre_stat         (WRITE) 体裁基准快照单条
 *   - bulk_create_genre_stats   (WRITE) 批量(merge 语义)
 *   - delete_genre_stat         (WRITE)
 *   - recompute_potential_scores (ACTION) 重算所有 DRAFT 选题的 potential_score
 *   - attribute_video           (ACTION) 单视频归因 — LLM 算"为什么数据好/差"
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SeriesAndStatsTools {

    private final ToolRegistry registry;
    private final SeriesRepository seriesRepo;
    private final SeriesHookRepository hookRepo;
    private final TopicRepository topicRepo;
    private final GenreStatSnapshotRepository genreStatRepo;
    private final InsightService insightService;
    private final VideoAttributionService videoAttributionService;
    private final PlatformTransactionManager txManager;

    /** 包多写操作:fulfill_hook_with_new_topic 要原子地建 Topic + 回填 hook,中途异常应回滚。 */
    private TransactionTemplate tx;

    @PostConstruct
    public void init() {
        this.tx = new TransactionTemplate(txManager);
        registry.register(new DismissSeriesHook());
        registry.register(new UndismissSeriesHook());
        registry.register(new FulfillHookWithNewTopic());
        registry.register(new CreateSeries());
        registry.register(new DeleteSeries());
        registry.register(new CreateGenreStat());
        registry.register(new BulkCreateGenreStats());
        registry.register(new DeleteGenreStat());
        registry.register(new RecomputePotentialScores());
        registry.register(new AttributeVideo());
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }

    // ============ dismiss_series_hook ============
    private class DismissSeriesHook implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "dismiss_series_hook",
                    "软忽略某 hook(设 dismissed_at=now)。幂等;已忽略再调不报错。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("hookId", Map.of("type", "integer")),
                            "required", List.of("hookId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("hookId").asLong();
            SeriesHook hook = hookRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hook " + id + " 不存在"));
            if (hook.getDismissedAt() == null) {
                hook.setDismissedAt(LocalDateTime.now());
                hookRepo.save(hook);
            }
            log.info("[Agent] dismiss_series_hook id={}", id);
            return Map.of("ok", true, "id", id, "dismissedAt", hook.getDismissedAt().toString());
        }
    }

    // ============ undismiss_series_hook ============
    private class UndismissSeriesHook implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "undismiss_series_hook",
                    "撤销忽略 hook。已兑现(toTopicId != null)的拒绝撤销。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("hookId", Map.of("type", "integer")),
                            "required", List.of("hookId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("hookId").asLong();
            SeriesHook hook = hookRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hook " + id + " 不存在"));
            if (hook.getToTopicId() != null) {
                throw new ResponseStatusException(CONFLICT,
                        "钩子已兑现到 topic T#" + hook.getToTopicId() + ",不可撤销忽略");
            }
            hook.setDismissedAt(null);
            hookRepo.save(hook);
            log.info("[Agent] undismiss_series_hook id={}", id);
            return Map.of("ok", true, "id", id);
        }
    }

    // ============ fulfill_hook_with_new_topic ============
    private class FulfillHookWithNewTopic implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "fulfill_hook_with_new_topic",
                    "把 hook 兑现成新 Topic — 一次事务里:(1) 建 Topic 落库 (2) 回填 hook.toTopicId。" +
                            "已兑现的 hook 拒绝重复兑现(409)。题材字段(dynasty/genre 等)可空,但 title 必填。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "hookId", Map.of("type", "integer"),
                                    "title", Map.of("type", "string", "description", "新 topic 标题"),
                                    "dynasty", Map.of("type", "string"),
                                    "genre", Map.of("type", "string"),
                                    "protagonist", Map.of("type", "string"),
                                    "hookType", Map.of("type", "string"),
                                    "emotion", Map.of("type", "string"),
                                    "durationMinutes", Map.of("type", "integer"),
                                    "historicalReference", Map.of("type", "string")
                            ),
                            "required", List.of("hookId", "title")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long hookId = args.get("hookId").asLong();
            SeriesHook hook = hookRepo.findById(hookId)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "hook " + hookId + " 不存在"));
            if (hook.getToTopicId() != null) {
                throw new ResponseStatusException(CONFLICT,
                        "钩子已兑现到 topic T#" + hook.getToTopicId() + ",不可重复兑现");
            }
            // 建 Topic + 回填 hook 必须在同一事务里:Topic 落库后回填若失败,孤儿 Topic + 钩子未兑现的状态会让用户困惑。
            Topic[] holder = new Topic[1];
            SeriesHook savedHook = tx.execute(status -> {
                Topic t = new Topic();
                String title = TextUtils.trimToMax(args.get("title").asText(), 200);
                t.setTitle(title);
                if (title != null && !title.isBlank()) {
                    t.setProjectName(title.substring(0, Math.min(title.length(), 10)));
                }
                if (args.hasNonNull("dynasty")) t.setDynasty(TextUtils.trimToMax(args.get("dynasty").asText(), 40));
                if (args.hasNonNull("genre")) t.setGenre(TextUtils.trimToMax(args.get("genre").asText(), 40));
                if (args.hasNonNull("protagonist")) t.setProtagonist(TextUtils.trimToMax(args.get("protagonist").asText(), 120));
                if (args.hasNonNull("hookType")) t.setHookType(TextUtils.trimToMax(args.get("hookType").asText(), 40));
                if (args.hasNonNull("emotion")) t.setEmotion(TextUtils.trimToMax(args.get("emotion").asText(), 40));
                if (args.hasNonNull("durationMinutes")) t.setDurationMinutes(args.get("durationMinutes").asInt());
                if (args.hasNonNull("historicalReference")) t.setHistoricalReference(args.get("historicalReference").asText());
                t.setStatus(TopicStatus.DRAFT);
                t.setSource("HOOK_FULFILL");
                t.setSourceHookId(hookId);
                // 系列继承自 hook.fromTopicId 对应 topic 的 series_id(如有)
                if (hook.getFromTopicId() != null) {
                    topicRepo.findById(hook.getFromTopicId())
                            .ifPresent(parent -> t.setSeriesId(parent.getSeriesId()));
                }
                Topic savedTopic = topicRepo.save(t);
                holder[0] = savedTopic;
                hook.setToTopicId(savedTopic.getId());
                return hookRepo.save(hook);
            });
            Topic savedTopic = holder[0];
            log.info("[Agent] fulfill_hook hookId={} → newTopicId={}", hookId, savedTopic.getId());
            return Map.of(
                    "ok", true,
                    "hookId", savedHook.getId(),
                    "newTopicId", savedTopic.getId(),
                    "newTopicTitle", savedTopic.getTitle()
            );
        }
    }

    // ============ create_series ============
    private class CreateSeries implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "create_series",
                    "新建一个内容系列。slug 全局唯一(冲突 409)。系列用于把多个相关 topic 串成连载。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "name", Map.of("type", "string", "description", "显示名,中文"),
                                    "slug", Map.of("type", "string", "description", "全局唯一 key,英文/拼音"),
                                    "description", Map.of("type", "string"),
                                    "coverUrl", Map.of("type", "string"),
                                    "status", Map.of("type", "string", "description", "如 active/paused")
                            ),
                            "required", List.of("name", "slug")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            String name = args.get("name").asText().trim();
            String slug = args.get("slug").asText().trim();
            seriesRepo.findBySlug(slug).ifPresent(existing -> {
                throw new ResponseStatusException(CONFLICT, "slug '" + slug + "' 已被占用");
            });
            Series s = new Series();
            s.setName(name);
            s.setSlug(slug);
            s.setDescription(args.hasNonNull("description") ? blankToNull(args.get("description").asText()) : null);
            s.setCoverUrl(args.hasNonNull("coverUrl") ? blankToNull(args.get("coverUrl").asText()) : null);
            if (args.hasNonNull("status")) s.setStatus(args.get("status").asText().trim());
            Series saved = seriesRepo.save(s);
            log.info("[Agent] create_series id={} slug={}", saved.getId(), saved.getSlug());
            return Map.of("ok", true, "id", saved.getId(), "name", saved.getName(), "slug", saved.getSlug());
        }
    }

    // ============ delete_series ============
    private class DeleteSeries implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_series",
                    "删除系列。系列下还有选题时拒绝(409),用户得先把选题改到别的系列或清空 series_id。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("seriesId", Map.of("type", "integer")),
                            "required", List.of("seriesId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("seriesId").asLong();
            Series s = seriesRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "系列 " + id + " 不存在"));
            long topicCount = topicRepo.findBySeriesIdOrderByIdDesc(id).size();
            if (topicCount > 0) {
                throw new ResponseStatusException(CONFLICT,
                        "系列下还有 " + topicCount + " 条选题,先把它们移走或清空 series_id");
            }
            seriesRepo.delete(s);
            log.info("[Agent] delete_series id={} name={}", id, s.getName());
            return Map.of("ok", true, "id", id, "name", s.getName());
        }
    }

    // ============ create_genre_stat ============
    private class CreateGenreStat implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "create_genre_stat",
                    "录入一条体裁基准快照(从抖音「投稿作品」xlsx 抄数)。" +
                            "(periodStart, periodEnd, platform, genre, vertical) 复合唯一,冲突 409。" +
                            "数值字段 (avgCtrPct/avgPlay5sPct 等) 全部可空,空 = 没数据。",
                    genreStatSchema()
            );
        }
        @Override
        public Object execute(JsonNode args) {
            LocalDate periodStart = LocalDate.parse(args.get("periodStart").asText());
            LocalDate periodEnd = LocalDate.parse(args.get("periodEnd").asText());
            String platform = args.get("platform").asText();
            String genre = args.get("genre").asText();
            String vertical = args.get("vertical").asText();
            genreStatRepo.findByPeriodStartAndPeriodEndAndPlatformAndGenreAndVertical(
                    periodStart, periodEnd, platform, genre, vertical)
                    .ifPresent(existing -> {
                        throw new ResponseStatusException(CONFLICT,
                                "同(周期, 平台, 体裁, 垂类)已存在 #" + existing.getId());
                    });
            GenreStatSnapshot s = applyGenreStat(new GenreStatSnapshot(), args, true);
            GenreStatSnapshot saved = genreStatRepo.save(s);
            log.info("[Agent] create_genre_stat id={} {}/{}/{}", saved.getId(), platform, genre, vertical);
            return Map.of("ok", true, "id", saved.getId(), "platform", platform, "genre", genre);
        }
    }

    private GenreStatSnapshot applyGenreStat(GenreStatSnapshot s, JsonNode args, boolean fullCreate) {
        if (fullCreate) {
            s.setPeriodStart(LocalDate.parse(args.get("periodStart").asText()));
            s.setPeriodEnd(LocalDate.parse(args.get("periodEnd").asText()));
            s.setPlatform(args.get("platform").asText().trim());
            s.setGenre(args.get("genre").asText().trim());
            s.setVertical(args.get("vertical").asText().trim());
            s.setSubmissionCount(args.hasNonNull("submissionCount") ? args.get("submissionCount").asInt() : 0);
        }
        if (args.hasNonNull("avgCtrPct")) s.setAvgCtrPct(new BigDecimal(args.get("avgCtrPct").asText()));
        if (args.hasNonNull("avgPlay5sPct")) s.setAvgPlay5sPct(new BigDecimal(args.get("avgPlay5sPct").asText()));
        if (args.hasNonNull("avgDrop2sPct")) s.setAvgDrop2sPct(new BigDecimal(args.get("avgDrop2sPct").asText()));
        if (args.hasNonNull("avgPlaySeconds")) s.setAvgPlaySeconds(new BigDecimal(args.get("avgPlaySeconds").asText()));
        if (args.hasNonNull("medianViews")) s.setMedianViews(args.get("medianViews").asLong());
        if (args.hasNonNull("avgLikes")) s.setAvgLikes(new BigDecimal(args.get("avgLikes").asText()));
        if (args.hasNonNull("avgComments")) s.setAvgComments(new BigDecimal(args.get("avgComments").asText()));
        if (args.hasNonNull("avgShares")) s.setAvgShares(new BigDecimal(args.get("avgShares").asText()));
        if (args.hasNonNull("notes")) s.setNotes(blankToNull(args.get("notes").asText()));
        return s;
    }

    private Map<String, Object> genreStatSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("periodStart", Map.of("type", "string", "description", "ISO 日期 YYYY-MM-DD")),
                        Map.entry("periodEnd", Map.of("type", "string", "description", "ISO 日期")),
                        Map.entry("platform", Map.of("type", "string")),
                        Map.entry("genre", Map.of("type", "string")),
                        Map.entry("vertical", Map.of("type", "string")),
                        Map.entry("submissionCount", Map.of("type", "integer")),
                        Map.entry("avgCtrPct", Map.of("type", "number")),
                        Map.entry("avgPlay5sPct", Map.of("type", "number")),
                        Map.entry("avgDrop2sPct", Map.of("type", "number")),
                        Map.entry("avgPlaySeconds", Map.of("type", "number")),
                        Map.entry("medianViews", Map.of("type", "integer")),
                        Map.entry("avgLikes", Map.of("type", "number")),
                        Map.entry("avgComments", Map.of("type", "number")),
                        Map.entry("avgShares", Map.of("type", "number")),
                        Map.entry("notes", Map.of("type", "string"))
                ),
                "required", List.of("periodStart", "periodEnd", "platform", "genre", "vertical")
        );
    }

    // ============ bulk_create_genre_stats ============
    private class BulkCreateGenreStats implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "bulk_create_genre_stats",
                    "批量导入体裁基准快照。merge 语义:同 (period, platform, genre, vertical) 已存在则部分更新,不存在则插。" +
                            "某行错塞 errors 不整批 rollback。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "rows", Map.of("type", "array",
                                            "description", "数组,每项跟 create_genre_stat 字段一致",
                                            "items", Map.of("type", "object"))
                            ),
                            "required", List.of("rows")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            JsonNode rows = args.get("rows");
            if (rows == null || !rows.isArray() || rows.isEmpty()) {
                return Map.of("inserted", 0, "updated", 0, "skipped", 0, "errors", List.of());
            }
            int inserted = 0, updated = 0, skipped = 0;
            List<String> errors = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                JsonNode row = rows.get(i);
                try {
                    LocalDate ps = LocalDate.parse(row.get("periodStart").asText());
                    LocalDate pe = LocalDate.parse(row.get("periodEnd").asText());
                    var existing = genreStatRepo.findByPeriodStartAndPeriodEndAndPlatformAndGenreAndVertical(
                            ps, pe, row.get("platform").asText(), row.get("genre").asText(), row.get("vertical").asText());
                    if (existing.isPresent()) {
                        applyGenreStat(existing.get(), row, false);
                        genreStatRepo.save(existing.get());
                        updated++;
                    } else {
                        genreStatRepo.save(applyGenreStat(new GenreStatSnapshot(), row, true));
                        inserted++;
                    }
                } catch (Exception e) {
                    skipped++;
                    errors.add("row " + i + ": " + e.getMessage());
                }
            }
            log.info("[Agent] bulk_create_genre_stats inserted={} updated={} skipped={}", inserted, updated, skipped);
            return Map.of("inserted", inserted, "updated", updated, "skipped", skipped, "errors", errors);
        }
    }

    // ============ delete_genre_stat ============
    private class DeleteGenreStat implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_genre_stat",
                    "删除一条体裁基准快照。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("id", Map.of("type", "integer")),
                            "required", List.of("id")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("id").asLong();
            if (!genreStatRepo.existsById(id)) {
                throw new ResponseStatusException(NOT_FOUND, "快照 " + id + " 不存在");
            }
            genreStatRepo.deleteById(id);
            log.info("[Agent] delete_genre_stat id={}", id);
            return Map.of("ok", true, "id", id);
        }
    }

    // ============ recompute_potential_scores ============
    private class RecomputePotentialScores implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "recompute_potential_scores",
                    "用真实数据重算所有 DRAFT 选题的 potential_score。批量更新,可能影响很多行。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "platform", Map.of("type", "string", "description", "可选;不传按全平台"),
                                    "days", Map.of("type", "integer", "description", "时间窗口天数,默认 30")
                            ),
                            "required", List.of()
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            String platform = args.hasNonNull("platform") ? args.get("platform").asText() : null;
            int days = args.hasNonNull("days") ? args.get("days").asInt() : 30;
            int updated = insightService.recomputePotentialScores(platform, days);
            log.info("[Agent] recompute_potential_scores platform={} days={} updated={}", platform, days, updated);
            return Map.of("ok", true, "updated", updated);
        }
    }

    // ============ attribute_video ============
    private class AttributeVideo implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "attribute_video",
                    "对单条 published_video 跑 LLM 归因(算'为什么数据好/差')。结果不入库,展示用。" +
                            "成本敏感:一次 LLM 调用。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("videoId", Map.of("type", "integer", "description", "published_video.id")),
                            "required", List.of("videoId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long videoId = args.get("videoId").asLong();
            VideoAttributionService.VideoAttributionResult r = videoAttributionService.analyze(videoId);
            log.info("[Agent] attribute_video videoId={}", videoId);
            return r;
        }
    }
}
