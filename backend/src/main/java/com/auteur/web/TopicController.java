package com.auteur.web;

import com.auteur.brainstorm.BrainstormRequest;
import com.auteur.brainstorm.BrainstormService;
import com.auteur.domain.Script;
import com.auteur.domain.ScriptRepository;
import com.auteur.domain.SeriesHookRepository;
import com.auteur.domain.Topic;
import com.auteur.domain.TopicRepository;
import com.auteur.domain.TopicStatus;
import com.auteur.script.ScriptService;
import com.auteur.video.DirectorNoteOptimizeService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/topics")
@RequiredArgsConstructor
public class TopicController {

    private final BrainstormService brainstormService;
    private final TopicRepository topicRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptService scriptService;
    private final SeriesHookRepository seriesHookRepository;
    private final DirectorNoteOptimizeService directorNoteOptimizeService;

    @PostMapping("/brainstorm")
    public List<Topic> brainstorm(@Valid @RequestBody BrainstormRequest req) {
        return brainstormService.brainstorm(req);
    }

    @GetMapping
    public Page<Topic> list(
            @RequestParam(defaultValue = "DRAFT") TopicStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "30") int size
    ) {
        Page<Topic> result = topicRepository.findByStatusOrderByIdDesc(
                status, PageRequest.of(page, Math.min(size, 100)));
        // 反向 enrich latestScriptId:一次 GROUP BY 拿到 [topicId,MAX(scriptId)]
        List<Long> topicIds = result.getContent().stream().map(Topic::getId).toList();
        if (!topicIds.isEmpty()) {
            Map<Long, Long> latestMap = scriptRepository.findLatestScriptIdsByTopicIds(topicIds).stream()
                    .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
            result.getContent().forEach(t -> t.setLatestScriptId(latestMap.get(t.getId())));
        }
        return result;
    }

    @GetMapping("/{id}")
    public Topic get(@PathVariable Long id) {
        Topic t = topicRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "topic " + id + " 不存在"));
        // 反向 enrich 来源 hook;hook 行可能被清理 — 容忍 NotFound,前端无来源卡自然降级。
        if (t.getSourceHookId() != null) {
            seriesHookRepository.findById(t.getSourceHookId()).ifPresent(t::setSourceHook);
        }
        return t;
    }

    /**
     * 多级链路追溯 —— 沿 source_hook_id → hook.from_topic_id 向上回溯,列出全部祖先。
     * 限深 20 防环 / 数据异常死循环。返回顺序:祖先在前,当前在末。
     */
    @GetMapping("/{id}/lineage")
    public List<LineageNode> lineage(@PathVariable Long id) {
        List<LineageNode> reversed = new java.util.ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        Long cursor = id;
        int safety = 0;
        while (cursor != null && safety++ < 20 && seen.add(cursor)) {
            Long currentId = cursor;
            Topic t = topicRepository.findById(currentId).orElse(null);
            if (t == null) break;
            // 当前节点的 sourceHook(若有)指向上一集 —— 把它的 fromTopicId 当下一跳;hookText 归属"当前"节点
            String hookSummary = null;
            Long fromScriptId = null;
            Long parentTopicId = null;
            if (t.getSourceHookId() != null) {
                var hookOpt = seriesHookRepository.findById(t.getSourceHookId());
                if (hookOpt.isPresent()) {
                    var h = hookOpt.get();
                    hookSummary = h.getNextEpisodeHint() != null ? h.getNextEpisodeHint() : h.getHookText();
                    fromScriptId = h.getFromScriptId();
                    parentTopicId = h.getFromTopicId();
                }
            }
            // 取该 topic 最新 script id 给前端"打开脚本"用
            Long latestScriptId = scriptRepository.findLatestScriptIdsByTopicIds(List.of(currentId)).stream()
                    .findFirst().map(row -> (Long) row[1]).orElse(null);
            reversed.add(new LineageNode(
                    t.getId(),
                    t.getTitle(),
                    t.getProjectName(),
                    latestScriptId,
                    hookSummary,
                    fromScriptId
            ));
            cursor = parentTopicId;
        }
        // reversed 是 [当前, 上一集, 上上集, ...];翻成 [起点, ..., 当前] 给前端按时间顺序渲染
        java.util.Collections.reverse(reversed);
        return reversed;
    }

    public record LineageNode(
            Long topicId,
            String title,
            String projectName,
            Long latestScriptId,
            /** 该节点是从哪条钩子兑现来的:nextEpisodeHint 优先,否则 hookText。起点节点为 null。 */
            String hookSummary,
            /** 该节点的钩子原文是从哪条 script 抽出来的(指向上一集 script)。起点为 null。 */
            Long fromScriptId
    ) {}

    /**
     * 部分更新:body 里没传的字段不动。source / aiSuggestedSeries / 时间戳系统管,不开放。
     */
    @PatchMapping("/{id}")
    @Transactional
    public Topic patch(@PathVariable Long id, @Valid @RequestBody TopicUpdateRequest req) {
        Topic t = topicRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "topic " + id + " 不存在"));

        if (req.getTitle() != null)               t.setTitle(req.getTitle());
        if (req.getProjectName() != null)         t.setProjectName(req.getProjectName());
        if (req.getDynasty() != null)             t.setDynasty(req.getDynasty());
        if (req.getGenre() != null)               t.setGenre(req.getGenre());
        if (req.getProtagonist() != null)         t.setProtagonist(req.getProtagonist());
        if (req.getHookType() != null)            t.setHookType(req.getHookType());
        if (req.getEmotion() != null)             t.setEmotion(req.getEmotion());
        if (req.getDurationMinutes() != null)     t.setDurationMinutes(req.getDurationMinutes());
        if (req.getPotentialScore() != null)      t.setPotentialScore(req.getPotentialScore());
        if (req.getHistoricalReference() != null) t.setHistoricalReference(req.getHistoricalReference());
        if (req.getSeriesId() != null)            t.setSeriesId(req.getSeriesId());
        if (req.getStatus() != null)              t.setStatus(req.getStatus());
        if (req.getNotes() != null)               t.setNotes(req.getNotes());
        if (req.getDirectorNote() != null) {
            String dn = req.getDirectorNote().trim();
            t.setDirectorNote(dn.isEmpty() || "null".equalsIgnoreCase(dn) ? null : dn);
        }
        // preset 绑定 + 输入字段 PATCH
        if (req.getPresetId() != null) t.setPresetId(req.getPresetId());
        if (req.getPresetInputJson() != null) {
            String pj = req.getPresetInputJson().trim();
            t.setPresetInputJson(pj.isEmpty() || "null".equalsIgnoreCase(pj) ? null : pj);
        }
        // 用户没显式给项目名又转入 SCHEDULED:从 title 截前 10 字兜底,避免后续工作台显示空。
        if (req.getStatus() == TopicStatus.SCHEDULED
                && (t.getProjectName() == null || t.getProjectName().isBlank())
                && t.getTitle() != null) {
            String title = t.getTitle();
            t.setProjectName(title.substring(0, Math.min(title.length(), 10)));
        }
        return topicRepository.save(t);
    }

    /**
     * 真删。注意 schema 上 script / video_asset / hook_chain → topic 是 ON DELETE CASCADE,
     * 直接删会连脚本/视频/钩链一起销毁 —— 所以这里先挡:有脚本 → 拒绝(409),让用户先归档(PATCH status=ARCHIVED)。
     * pipeline_run.topic_id 没 FK,删完会变孤儿(append-only 历史,可接受)。
     */
    @DeleteMapping("/{id}")
    @Transactional
    public void delete(@PathVariable Long id) {
        Topic t = topicRepository.findById(id).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "topic " + id + " 不存在"));
        long scriptCount = scriptRepository.countByTopicId(id);
        if (scriptCount > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "选题已有 " + scriptCount + " 条脚本,删除会级联销毁;请先删脚本或将选题归档(PATCH status=ARCHIVED)");
        }
        topicRepository.delete(t);
    }

    @PostMapping("/{id}/scripts/generate")
    public Script generateScript(@PathVariable Long id) {
        return scriptService.generate(id);
    }

    /** 异步脚本生成,立即返回 runId,前端轮询 GET /api/runs/{runId},DONE 后用 run.scriptId 跳详情。 */
    @PostMapping("/{id}/scripts/generate-async")
    public Map<String, Object> generateScriptAsync(@PathVariable Long id) {
        Long runId = scriptService.generateAsync(id, "API");
        return Map.of("runId", runId);
    }

    /**
     * 导演笔记 "AI 智能填充":用户在 DirectorNoteDrawer 里描述对当前笔记的诉求,LLM 综合
     * 已填内容(可能含未保存草稿)+ 用户诉求重写整份 DirectorNote。不落库,只返回建议。
     */
    @PostMapping("/{id}/director-note/optimize")
    public DirectorNoteOptimizeService.OptimizeResponse optimizeDirectorNote(
            @PathVariable Long id,
            @RequestBody DirectorNoteOptimizeService.OptimizeRequest req
    ) {
        return directorNoteOptimizeService.optimize(id, req);
    }
}
