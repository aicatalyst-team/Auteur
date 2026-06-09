package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
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
