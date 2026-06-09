package com.auteur.agent.tools;

import com.auteur.agent.ActionToolHandler;
import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.domain.CriticLog;
import com.auteur.domain.CriticLogRepository;
import com.auteur.domain.SeriesHook;
import com.auteur.insights.InsightService;
import com.auteur.insights.WeeklyReviewService;
import com.auteur.llm.ChatRequest;
import com.auteur.script.HookExtractor;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * D 档 · 反思与洞察工具:把 Auteur 业务层既有的 critic / insight / weekly-review 等组件包成 tool。
 *
 * 5 个工具:
 *   - list_critic_logs       (READ)   读 critic_log 表 — 让 LLM 看到历史自审分,而不是重跑(贵)
 *   - get_top_bottom_videos  (READ)   InsightService.topBottom — 列前 N / 后 N
 *   - get_dimension_weights  (READ)   InsightService.dimensionWeights — 哪些维度对完播相关性最高
 *   - generate_weekly_review (ACTION) WeeklyReviewService.generate — 跑 LLM 出周报,贵
 *   - extract_series_hook    (WRITE)  HookExtractor.extract — 从已发布脚本反提下集 hook,写 series_hook 表
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReflectionTools {

    private final ToolRegistry registry;
    private final CriticLogRepository criticLogRepo;
    private final InsightService insightService;
    private final WeeklyReviewService weeklyReviewService;
    private final HookExtractor hookExtractor;

    @PostConstruct
    public void init() {
        registry.register(new ListCriticLogs());
        registry.register(new GetTopBottomVideos());
        registry.register(new GetDimensionWeights());
        registry.register(new GenerateWeeklyReview());
        registry.register(new ExtractSeriesHook());
    }

    // ============ list_critic_logs ============
    private class ListCriticLogs implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "list_critic_logs",
                    "读某 script 的历史自审记录(脚本/分镜 critic 角色给出的分数和 decision)。" +
                            "用于回答'这个脚本被哪些角色审过了''分数怎么样'之类问题,而不必重跑 critic(贵)。" +
                            "默认只返最新 20 条;老脚本反复审过几百次时全返会灌爆上下文。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "limit", Map.of("type", "integer",
                                            "description", "返回条数,默认 20,最多 100")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            int limit = args.hasNonNull("limit")
                    ? Math.min(100, Math.max(1, args.get("limit").asInt()))
                    : 20;
            List<CriticLog> logs = criticLogRepo.findByScriptIdOrderByCreatedAtDesc(id, PageRequest.of(0, limit));
            List<Map<String, Object>> rows = logs.stream().map(l -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", l.getId());
                m.put("role", l.getRole());
                m.put("score", l.getScore());
                m.put("decision", l.getDecision());
                m.put("contentType", l.getContentType());
                m.put("issuesJson", l.getIssuesJson());
                m.put("feedback", l.getFeedback());
                m.put("createdAt", l.getCreatedAt() == null ? null : l.getCreatedAt().toString());
                return m;
            }).toList();
            return Map.of("scriptId", id, "limit", limit, "count", rows.size(), "logs", rows);
        }
    }

    // ============ get_top_bottom_videos ============
    private class GetTopBottomVideos implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_top_bottom_videos",
                    "拉某平台近 N 天的前 N / 后 N 视频(按潜力分排序),用于回答'最近哪些视频做得最好''哪些做得最差'。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "platform", Map.of("type", "string", "description", "如 douyin / kuaishou"),
                                    "days", Map.of("type", "integer", "description", "时间窗口,默认 30"),
                                    "n", Map.of("type", "integer", "description", "前/后各取 N 条,默认 5"),
                                    "contentType", Map.of("type", "string", "description", "可选,按内容形态过滤")
                            ),
                            "required", List.of("platform")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String platform = args.get("platform").asText();
            int days = args.hasNonNull("days") ? args.get("days").asInt() : 30;
            int n = args.hasNonNull("n") ? args.get("n").asInt() : 5;
            String contentType = args.hasNonNull("contentType") ? args.get("contentType").asText() : null;
            return insightService.topBottom(platform, days, n, contentType);
        }
    }

    // ============ get_dimension_weights ============
    private class GetDimensionWeights implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "get_dimension_weights",
                    "拉某平台近 N 天的维度权重报告 — 各维度(钩子/朝代/题材等)对完播率的统计相关性。" +
                            "用于'什么钩子最有效''哪种题材表现最好'类问题。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "platform", Map.of("type", "string"),
                                    "days", Map.of("type", "integer", "description", "默认 30"),
                                    "minSamples", Map.of("type", "integer", "description", "每个维度值至少几条样本才统计,默认 3"),
                                    "contentType", Map.of("type", "string", "description", "可选,按内容形态过滤")
                            ),
                            "required", List.of("platform")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String platform = args.get("platform").asText();
            int days = args.hasNonNull("days") ? args.get("days").asInt() : 30;
            int minSamples = args.hasNonNull("minSamples") ? args.get("minSamples").asInt() : 3;
            String contentType = args.hasNonNull("contentType") ? args.get("contentType").asText() : null;
            return insightService.dimensionWeights(platform, days, minSamples, contentType);
        }
    }

    // ============ generate_weekly_review ============
    private class GenerateWeeklyReview implements ActionToolHandler {

        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_weekly_review",
                    "跑 LLM 生成周复盘报告(highlights/lessons/experiments/nextWeek)。成本敏感:一次几千 token。" +
                            "样本不足 3 条会返 fallback 文案不烧钱。结果不自动落库,LLM 应让用户决定是否保存。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "platform", Map.of("type", "string"),
                                    "days", Map.of("type", "integer", "description", "时间窗口,默认 7")
                            ),
                            "required", List.of("platform")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String platform = args.get("platform").asText();
            int days = args.hasNonNull("days") ? args.get("days").asInt() : 7;
            WeeklyReviewService.WeeklyReviewResult r = weeklyReviewService.generate(platform, days);
            log.info("[Agent] generate_weekly_review platform={} days={} fallback={}", platform, days, r.fallback());
            return r;
        }
    }

    // ============ extract_series_hook ============
    private class ExtractSeriesHook implements WriteToolHandler {

        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "extract_series_hook",
                    "从某 script 反向提取下集预告 hook,写入 series_hook 表(后续选题脑暴会消费)。" +
                            "适合脚本生成后或视频发布后调一次。重复调会写多条 hook。",
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
            SeriesHook hook = hookExtractor.extract(id);
            log.info("[Agent] extract_series_hook scriptId={} → hookId={}",
                    id, hook == null ? null : hook.getId());
            if (hook == null) {
                return Map.of("ok", false, "reason", "未提取到 hook(脚本可能没有合适的钩子)");
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", true);
            out.put("hookId", hook.getId());
            out.put("fromScriptId", hook.getFromScriptId());
            out.put("fromTopicId", hook.getFromTopicId());
            out.put("hookText", hook.getHookText());
            out.put("nextEpisodeHint", hook.getNextEpisodeHint());
            out.put("strength", hook.getStrength());
            out.put("suggestedTitle", hook.getSuggestedTitle());
            out.put("createdAt", hook.getCreatedAt() == null ? null : hook.getCreatedAt().toString());
            return out;
        }
    }
}
