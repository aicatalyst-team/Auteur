package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.domain.PipelineRun;
import com.auteur.domain.PipelineRunRepository;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.domain.ImageAssetRepository;
import com.auteur.llm.ChatRequest;
import com.auteur.pipeline.PipelineRunDto;
import com.auteur.script.ScriptListDto;
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

    @PostConstruct
    public void init() {
        registry.register(new GetRunStatus());
        registry.register(new ListRecentScripts());
        registry.register(new GetScriptSummary());
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
}
