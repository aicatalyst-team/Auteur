package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.domain.DirectorNoteAddendum;
import com.auteur.domain.DirectorNoteAddendumRepository;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.llm.ChatRequest;
import com.auteur.pipeline.PipelineRunDto;
import com.auteur.script.ScriptListDto;
import com.auteur.video.DirectorNoteService;
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
 * 圈二配套的只读工具(让 LLM 在触发流水线后能查进度 + 拿脚本上下文)。
 *
 *   - get_run_status      : 查 PipelineRun 状态(LLM 触发动作工具后用来轮询)
 *   - list_recent_scripts : 列出最近 N 个 script(选目标用)
 *   - get_script_summary  : 单 script 概览(不返 fullText 大字段,只返摘要 + 各类资产计数)
 *   - get_director_notes  : 读 topic 的"剧组群聊"累积笔记(每个 stage 跑完 append 的 addendum)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ScriptReadTools {

    private final ToolRegistry registry;
    private final PipelineRunRepository runRepo;
    private final ScriptRepository scriptRepo;
    private final StoryboardShotRepository shotRepo;
    private final ImageAssetRepository imageRepo;
    private final TopicRepository topicRepo;
    private final DirectorNoteAddendumRepository addendumRepo;
    private final DirectorNoteService directorNoteService;

    @PostConstruct
    public void init() {
        registry.register(new GetRunStatus());
        registry.register(new ListRecentScripts());
        registry.register(new GetScriptSummary());
        registry.register(new GetDirectorNotes());
    }

    private class GetRunStatus implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_run_status",
                    "查 PipelineRun 的当前状态(PENDING/RUNNING/DONE/FAILED 等)。" +
                            "ACTION 工具触发后用这个轮询;每次间隔 3-5 秒比较合理。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("runId", Map.of("type", "integer")),
                            "required", List.of("runId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("runId").asLong();
            PipelineRun run = runRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "run id=" + id + " 不存在"));
            return PipelineRunDto.from(run);
        }
    }

    private class ListRecentScripts implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "list_recent_scripts",
                    "列出最近 N 个脚本(默认 10,最多 30)。每条带最近一次 PipelineRun 的 stage/status,方便 LLM 选目标。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "limit", Map.of("type", "integer", "description", "默认 10"),
                                    "topicId", Map.of("type", "integer", "description", "可选,按选题过滤")
                            ),
                            "required", List.of()
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            int limit = args.hasNonNull("limit") ? Math.min(30, Math.max(1, args.get("limit").asInt())) : 10;
            Long topicId = args.hasNonNull("topicId") ? args.get("topicId").asLong() : null;
            PageRequest pr = PageRequest.of(0, limit);
            List<Script> scripts = topicId != null
                    ? scriptRepo.findByTopicIdOrderByIdDesc(topicId, pr).getContent()
                    : scriptRepo.findByOrderByIdDesc(pr).getContent();
            List<Long> ids = scripts.stream().map(Script::getId).toList();
            Map<Long, PipelineRun> latestByScript = ids.isEmpty()
                    ? Map.of()
                    : runRepo.findLatestRunsByScriptIds(ids).stream()
                            .collect(java.util.stream.Collectors.toMap(PipelineRun::getScriptId, r -> r, (a, b) -> a));
            List<ScriptListDto> dtos = scripts.stream()
                    .map(s -> ScriptListDto.from(s, null, latestByScript.get(s.getId())))
                    .toList();
            return Map.of("count", dtos.size(), "scripts", dtos);
        }
    }

    private class GetScriptSummary implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_script_summary",
                    "单个脚本的轻量概览(不返 fullText,只返字段 + 资产计数 + 最近 run)。" +
                            "适合 LLM 触发流水线前确认目标。需要全文请让用户去网页 UI 查。",
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
            Script s = scriptRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "script id=" + id + " 不存在"));
            List<StoryboardShot> shots = shotRepo.findByScriptIdOrderByShotIndexAsc(id);
            int shotCount = shots.size();
            // imageRepo 没有按 scriptId 查的方法,迭代 shot 累加;一个 script 通常 20-28 镜,可接受
            long imageCount = shots.stream().mapToLong(sh -> imageRepo.countByShotId(sh.getId())).sum();
            PipelineRun latest = runRepo.findLatestRunsByScriptIds(List.of(id))
                    .stream().findFirst().orElse(null);

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", s.getId());
            out.put("topicId", s.getTopicId());
            out.put("version", s.getVersion());
            out.put("status", s.getStatus());
            out.put("modelUsed", s.getModelUsed());
            out.put("wordCount", s.getWordCount());
            out.put("durationSeconds", s.getDurationSeconds());
            out.put("reviewScore", s.getReviewScore());
            out.put("createdAt", s.getCreatedAt() == null ? null : s.getCreatedAt().toString());
            out.put("updatedAt", s.getUpdatedAt() == null ? null : s.getUpdatedAt().toString());
            out.put("fullTextPreview", preview(s.getFullText()));
            out.put("shotCount", shotCount);
            out.put("imageCount", imageCount);
            if (latest != null) {
                out.put("latestRun", Map.of(
                        "runId", latest.getId(),
                        "stage", latest.getStage(),
                        "status", latest.getStatus(),
                        "at", latest.getCreatedAt() == null ? null : latest.getCreatedAt().toString()
                ));
            }
            return out;
        }
    }

    private static String preview(String s) {
        if (s == null) return null;
        if (s.length() <= 300) return s;
        return s.substring(0, 300) + "…(共" + s.length() + "字)";
    }

    // ============ get_director_notes ============
    /**
     * 读 topic 的"剧组群聊"累积笔记。流水线运行时各 stage(编剧/摄影/录音/副导演)跑完会 append 一条
     * ≤500 字 addendum,下游 stage 拼成"剧组群聊"塞进 prompt。
     *
     * 用户/agent 可调本工具看"流水线刚跑完各角色都说了啥",理解决策链路。
     * Topic.directorNote(用户手动写的)是另一回事 — 那个用 update_topic / get_topic 看。
     */
    private class GetDirectorNotes implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_director_notes",
                    "读某 topic 的'剧组群聊'(各 stage 跑完留下的累积笔记)+ Topic.directorNote(用户手写的整体方向)。" +
                            "用于回答'编剧/摄影定下了什么方向''为什么这么决策'类问题。",
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

            List<DirectorNoteAddendum> rows = addendumRepo.findByTopicIdOrderByCreatedAtAsc(id);
            List<Map<String, Object>> addendaList = rows.stream().map(r -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", r.getId());
                m.put("role", r.getRole());
                m.put("roleDisplay", roleDisplay(r.getRole()));
                m.put("text", r.getAddendumText());
                m.put("createdAt", r.getCreatedAt() == null ? null : r.getCreatedAt().toString());
                return m;
            }).toList();

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("topicId", id);
            out.put("topicTitle", t.getTitle());
            // Topic.directorNote — 用户手写的整体方向
            out.put("directorNote", t.getDirectorNote());
            // addendum — 流水线各 stage 累积的"剧组群聊"
            out.put("addendaCount", addendaList.size());
            out.put("addenda", addendaList);
            // 拼好的"剧组群聊"文本(下游 prompt 实际看到的样式),空时返空串
            out.put("groupChatBlock", directorNoteService.buildBlock(id));
            return out;
        }

        private String roleDisplay(String role) {
            if (role == null) return "未知";
            return switch (role) {
                case "SCRIPT" -> "编剧";
                case "STORYBOARD" -> "摄影";
                case "VOICE" -> "录音";
                case "ASSISTANT_DIRECTOR" -> "副导演";
                default -> role;
            };
        }
    }
}
