package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.brainstorm.BrainstormRequest;
import com.auteur.brainstorm.BrainstormService;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.llm.ChatRequest;
import com.auteur.script.ScriptService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 补全创作链路最前两环:
 *   选题脑暴 → 选题列表/详情 → 从某 topic 第一次生成脚本
 *
 * 之前的圈二只覆盖"已有 script 之后"(regenerate_script / generate_storyboard / ...),
 * 没有从零起点的入口,导致"对话式"的完整闭环断掉。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TopicTools {

    private static final String SOURCE = "agent";

    private final ToolRegistry registry;
    private final TopicRepository topicRepo;
    private final BrainstormService brainstormService;
    private final ScriptService scriptService;

    @PostConstruct
    public void init() {
        registry.register(new ListTopics());
        registry.register(new GetTopic());
        registry.register(new CreateTopic());
        registry.register(new BrainstormTopics());
        registry.register(new GenerateScriptFromTopic());
    }

    private Map<String, Object> summarize(Topic t) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", t.getId());
        m.put("title", t.getTitle());
        m.put("projectName", t.getProjectName());
        m.put("dynasty", t.getDynasty());
        m.put("genre", t.getGenre());
        m.put("protagonist", t.getProtagonist());
        m.put("hookType", t.getHookType());
        m.put("emotion", t.getEmotion());
        m.put("durationMinutes", t.getDurationMinutes());
        m.put("potentialScore", t.getPotentialScore());
        m.put("status", t.getStatus());
        m.put("source", t.getSource());
        m.put("presetId", t.getPresetId());
        m.put("createdAt", t.getCreatedAt() == null ? null : t.getCreatedAt().toString());
        return m;
    }

    // ============ list_topics ============
    private class ListTopics implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "list_topics",
                    "列出某状态的选题(默认 DRAFT)。状态枚举:DRAFT/SCHEDULED/PRODUCED/PUBLISHED/ARCHIVED。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "status", Map.of("type", "string",
                                            "enum", List.of("DRAFT", "SCHEDULED", "PRODUCED", "PUBLISHED", "ARCHIVED"),
                                            "description", "默认 DRAFT"),
                                    "limit", Map.of("type", "integer", "description", "默认 20,最多 100")
                            ),
                            "required", List.of()
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            TopicStatus status = TopicStatus.DRAFT;
            if (args.hasNonNull("status")) {
                status = TopicStatus.valueOf(args.get("status").asText());
            }
            int limit = args.hasNonNull("limit")
                    ? Math.min(100, Math.max(1, args.get("limit").asInt()))
                    : 20;
            List<Topic> topics = topicRepo.findByStatusOrderByIdDesc(status, PageRequest.of(0, limit)).getContent();
            return Map.of(
                    "status", status,
                    "count", topics.size(),
                    "topics", topics.stream().map(TopicTools.this::summarize).toList()
            );
        }
    }

    // ============ get_topic ============
    private class GetTopic implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_topic",
                    "按 id 读单个选题完整信息(含 director_note / preset_input_json / source_hook_id 等)。",
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
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "topic id=" + id + " 不存在"));
            Map<String, Object> m = new LinkedHashMap<>(summarize(t));
            m.put("historicalReference", t.getHistoricalReference());
            m.put("notes", t.getNotes());
            m.put("directorNote", t.getDirectorNote());
            m.put("presetInputJson", t.getPresetInputJson());
            m.put("presetVersionUsed", t.getPresetVersionUsed());
            m.put("seriesId", t.getSeriesId());
            m.put("sourceHookId", t.getSourceHookId());
            m.put("latestScriptId", t.getLatestScriptId());
            m.put("aiSuggestedSeries", t.getAiSuggestedSeries());
            return m;
        }
    }

    // ============ create_topic ============
    /**
     * 直接插入一条选题(不跑脑暴 LLM)。前端没有这个按钮 — 网页上选题只通过脑暴/hook 兑现产生。
     * 但 agent 场景下用户经常说"我想做 X 这个选题",每次都跑 brainstorm 太贵。直接落库一条 DRAFT 即可。
     *
     * source = 'AGENT_MANUAL',跟 AI_BRAINSTORM / HOOK_FULFILL 区分开,后续数据分析能识别。
     */
    private class CreateTopic implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "create_topic",
                    "直接创建一条选题(不跑脑暴 LLM)。落库 status=DRAFT、source=AGENT_MANUAL。" +
                            "title 必填;其他字段(dynasty/genre/protagonist/hookType/emotion 等)可空但建议填,影响后续脚本生成。" +
                            "想要批量候选用 brainstorm_topics 跑 LLM。",
                    Map.ofEntries(
                            Map.entry("type", "object"),
                            Map.entry("properties", Map.ofEntries(
                                    Map.entry("title", Map.of("type", "string")),
                                    Map.entry("projectName", Map.of("type", "string", "description", "项目名;不填会从 title 截前 10 字兜底")),
                                    Map.entry("dynasty", Map.of("type", "string", "description", "朝代/时代背景")),
                                    Map.entry("genre", Map.of("type", "string", "description", "题材类型")),
                                    Map.entry("protagonist", Map.of("type", "string", "description", "主角设定")),
                                    Map.entry("hookType", Map.of("type", "string", "description", "钩子类型")),
                                    Map.entry("emotion", Map.of("type", "string", "description", "情绪基调")),
                                    Map.entry("durationMinutes", Map.of("type", "integer", "description", "目标时长分钟数")),
                                    Map.entry("potentialScore", Map.of("type", "number", "description", "潜力分,空时由后续 recompute 算")),
                                    Map.entry("historicalReference", Map.of("type", "string", "description", "历史依据/灵感来源")),
                                    Map.entry("seriesId", Map.of("type", "integer", "description", "归属系列 id,可空")),
                                    Map.entry("notes", Map.of("type", "string")),
                                    Map.entry("directorNote", Map.of(
                                            "description", "导演笔记 — 跨角色共享的叙事/视觉方向。" +
                                                    "**字段是 MySQL JSON 列**,前端按结构化对象存储 8 个字段(tone/pacing/" +
                                                    "narrativeArc[A-E]/visualStyle/protagonistVibe/keyMoments/highlightThemes/directorNotes)。" +
                                                    "强烈建议传完整 JSON 对象 — 详见 read_skill('topic-creation') 里的 schema 模板。" +
                                                    "若只传纯字符串,工具会自动包装成 {directorNotes:\"<text>\"} — 前端能读但 narrativeArc 等关键段空着,下游 LLM 角色会失参考。"
                                    )),
                                    Map.entry("presetId", Map.of("type", "integer",
                                            "description", "**强烈建议填** — 决定后续脚本/分镜/出图用哪个内容形态。不填后续 generate_script_from_topic 会失败")),
                                    Map.entry("presetInputJson", Map.of("type", "string",
                                            "description", "preset 的输入字段 JSON(身份标签卡之类),按 preset.input_schema_json 的结构填"))
                            )),
                            Map.entry("required", List.of("title"))
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            Topic t = new Topic();
            String title = args.get("title").asText();
            t.setTitle(title);
            // projectName:不传则从 title 截前 10 字
            if (args.hasNonNull("projectName")) {
                t.setProjectName(args.get("projectName").asText());
            } else if (title != null && !title.isBlank()) {
                t.setProjectName(title.substring(0, Math.min(title.length(), 10)));
            }
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
            if (args.hasNonNull("notes")) t.setNotes(args.get("notes").asText());
            if (args.hasNonNull("directorNote")) t.setDirectorNote(MoreOpsTools.normalizeDirectorNote(args.get("directorNote")));
            if (args.hasNonNull("presetId")) t.setPresetId(args.get("presetId").asLong());
            if (args.hasNonNull("presetInputJson")) {
                JsonNode v = args.get("presetInputJson");
                t.setPresetInputJson(v.isTextual() ? v.asText() : v.toString());
            }
            t.setStatus(TopicStatus.DRAFT);
            t.setSource("AGENT_MANUAL");

            Topic saved = topicRepo.save(t);
            log.info("[Agent] create_topic id={} title={} presetId={}", saved.getId(), saved.getTitle(), saved.getPresetId());

            // 关键字段缺失时主动报警 — 让 LLM 看到 hint 就知道该回头补,而不是把不合格 topic 放走。
            // 兜底逻辑(projectName 截 title 前 10 字)是"防止 NULL 崩溃",不该当默认行为。
            List<String> missing = new java.util.ArrayList<>();
            if (saved.getProtagonist() == null || saved.getProtagonist().isBlank()) missing.add("protagonist");
            // directorNote: 完全缺 → 报缺;只有 directorNotes 一字段(走了兜底) → 报"结构不全"
            boolean directorNoteMissing = saved.getDirectorNote() == null || saved.getDirectorNote().isBlank();
            boolean directorNoteWeak = !directorNoteMissing && MoreOpsTools.isWeakDirectorNote(saved.getDirectorNote());
            if (directorNoteMissing) missing.add("directorNote");
            if (saved.getEmotion() == null || saved.getEmotion().isBlank()) missing.add("emotion");
            // projectName 即便有 — 如果是从 title 截前 10 字兜底来的,标出来
            boolean projectNameAuto = !args.hasNonNull("projectName")
                    && saved.getProjectName() != null
                    && saved.getProjectName().equals(saved.getTitle() == null ? null
                            : saved.getTitle().substring(0, Math.min(saved.getTitle().length(), 10)));

            StringBuilder hint = new StringBuilder();
            if (saved.getPresetId() == null) {
                hint.append("⚠️ 未指定 presetId,后续 generate_script_from_topic 会失败 — 建议先 update_topic 补 presetId。");
            }
            if (!missing.isEmpty()) {
                if (hint.length() > 0) hint.append(" ");
                hint.append("⚠️ 关键字段为空: ").append(missing)
                        .append("。下游脚本/分镜会因此漂移,建议立即 update_topic 补全 — 让用户告诉你或基于上下文给出值。");
            }
            if (directorNoteWeak) {
                if (hint.length() > 0) hint.append(" ");
                hint.append("⚠️ directorNote 结构不全 — 你只填了 directorNotes 一字段(纯文本兜底包装),")
                        .append("缺 tone/pacing/narrativeArc 等核心结构。下游编剧 LLM 看不到 narrativeArc 5 段指导会自由发挥。")
                        .append("**立即** read_skill('topic-creation') 看完整 JSON schema,然后 update_topic 把 directorNote 重填成完整对象。")
                        .append("如果用户描述里有'分几个阶段/几段/几幕'这种结构线索,直接拆进 narrativeArc[A-E] 5 段。");
            }
            if (projectNameAuto) {
                if (hint.length() > 0) hint.append(" ");
                hint.append("ℹ️ projectName 是从 title 截前 10 字兜底的(=\"")
                        .append(saved.getProjectName()).append("\"),通常质量差,建议你帮用户起一个有辨识度的 4-10 字短名后 update_topic。");
            }
            if (hint.length() == 0) {
                hint.append("✓ 字段完备。可调 generate_script_from_topic(topicId=").append(saved.getId()).append(") 生成脚本。");
            }

            return Map.of(
                    "ok", true,
                    "id", saved.getId(),
                    "title", saved.getTitle(),
                    "status", saved.getStatus(),
                    "presetId", saved.getPresetId(),
                    "missingKeyFields", missing,
                    "directorNoteWeak", directorNoteWeak,
                    "hint", hint.toString()
            );
        }
    }

    // ============ brainstorm_topics ============
    private class BrainstormTopics implements ToolHandler {
        @Override public Risk risk() { return Risk.ACTION; }
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "brainstorm_topics",
                    "调用选题脑暴,生成 N 个候选选题(按权重打分)落库 status=DRAFT。" +
                            "成本敏感:跑一次 LLM(旗舰模型),通常 30-90s。presetId 必填,决定生成什么内容形态。" +
                            "useDataDriven=true 时会基于近 N 天的播放数据辅助打分。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "presetId", Map.of("type", "integer", "description", "选题用哪个 preset(决定内容形态)"),
                                    "n", Map.of("type", "integer", "description", "生成几个候选,默认 20"),
                                    "archiveHint", Map.of("type", "string", "description", "可选;希望避开的方向/题材,默认'无'"),
                                    "doneTopics", Map.of("type", "string", "description", "可选;已经做过的题(避免撞车),默认'无'"),
                                    "useDataDriven", Map.of("type", "boolean", "description", "是否用历史数据辅助打分,默认 false"),
                                    "platform", Map.of("type", "string", "description", "数据源平台(只在 useDataDriven=true 时用)"),
                                    "windowDays", Map.of("type", "integer", "description", "数据窗口天数,默认 30"),
                                    "model", Map.of("type", "string", "description", "覆盖默认模型,可空")
                            ),
                            "required", List.of("presetId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            BrainstormRequest req = new BrainstormRequest();
            req.setPresetId(args.get("presetId").asLong());
            if (args.hasNonNull("n")) req.setN(args.get("n").asInt());
            if (args.hasNonNull("archiveHint")) req.setArchiveHint(args.get("archiveHint").asText());
            if (args.hasNonNull("doneTopics")) req.setDoneTopics(args.get("doneTopics").asText());
            if (args.hasNonNull("useDataDriven")) req.setUseDataDriven(args.get("useDataDriven").asBoolean());
            if (args.hasNonNull("platform")) req.setPlatform(args.get("platform").asText());
            if (args.hasNonNull("windowDays")) req.setWindowDays(args.get("windowDays").asInt());
            if (args.hasNonNull("model")) req.setModel(args.get("model").asText());

            List<Topic> topics = brainstormService.brainstorm(req);
            log.info("[Agent] brainstorm_topics presetId={} n={} → got {} topics",
                    req.getPresetId(), req.getN(), topics.size());
            return Map.of(
                    "ok", true,
                    "count", topics.size(),
                    "topics", topics.stream().map(TopicTools.this::summarize).toList()
            );
        }
    }

    // ============ generate_script_from_topic ============
    private class GenerateScriptFromTopic implements ToolHandler {
        @Override public Risk risk() { return Risk.ACTION; }
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_script_from_topic",
                    "从某 topic **第一次**生成脚本(立即返回 runId)。区别于 regenerate_script(对已有 script 改版重生)。" +
                            "anchor 可选,塞进 user prompt 末尾的自由指令(如'钩子要更悬疑')。通常 30-60s。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "topicId", Map.of("type", "integer"),
                                    "anchor", Map.of("type", "string",
                                            "description", "可选指令文本,塞进 prompt 引导生成方向")
                            ),
                            "required", List.of("topicId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("topicId").asLong();
            String anchor = args.hasNonNull("anchor") ? args.get("anchor").asText() : null;
            Long runId = scriptService.generateAsync(id, anchor, SOURCE);
            log.info("[Agent] generate_script_from_topic topicId={} anchor={} → run={}",
                    id, anchor != null, runId);
            return Map.of(
                    "ok", true,
                    "runId", runId,
                    "topicId", id,
                    "hint", "脚本生成已发起,通常 30-60s。可轮询 get_run_status(runId);DONE 后用 list_recent_scripts 找到新脚本。"
            );
        }
    }
}
