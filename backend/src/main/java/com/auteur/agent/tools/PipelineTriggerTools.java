package com.auteur.agent.tools;

import com.auteur.agent.ActionToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.cover.CoverGenerationService;
import com.auteur.image.ImageAuditService;
import com.auteur.image.ImageGenService;
import com.auteur.llm.ChatRequest;
import com.auteur.script.FactCheckService;
import com.auteur.script.ScriptService;
import com.auteur.storyboard.StoryboardService;
import com.auteur.video.VideoAssemblyService;
import com.auteur.voice.VoiceGenService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 圈二:流水线触发工具集(全部 ACTION,必走 HITL approval)。
 *
 * 所有工具立即返回 runId,让 LLM 提示用户用 get_run_status 轮询,
 * 不在 Agent 控制台同步等待 — 长任务跑 5-15 分钟,SSE 撑不住。
 *
 * 触发源 triggeredBy 一律传 "agent",方便从 cost_log/run 历史里区分对话发起的 vs UI 发起的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PipelineTriggerTools {

    private static final String SOURCE = "agent";

    private final ToolRegistry registry;
    private final ScriptService scriptService;
    private final StoryboardService storyboardService;
    private final ImageGenService imageGenService;
    private final ImageAuditService imageAuditService;
    private final VoiceGenService voiceGenService;
    private final VideoAssemblyService videoAssemblyService;
    private final CoverGenerationService coverGenerationService;
    private final FactCheckService factCheckService;

    @PostConstruct
    public void init() {
        registry.register(new RegenerateScript());
        registry.register(new GenerateStoryboard());
        registry.register(new GenerateImages());
        registry.register(new AuditImages());
        registry.register(new RegenerateImageForShot());
        registry.register(new GenerateVoice());
        registry.register(new RenderVideo());
        registry.register(new GenerateCovers());
        registry.register(new RunFactCheck());
    }

    private static Map<String, Object> ok(Long runId, String hint) {
        return Map.of(
                "ok", true,
                "runId", runId,
                "hint", hint
        );
    }

    // -------- regenerate_script --------
    private class RegenerateScript implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "regenerate_script",
                    "重新生成脚本(version+1)。可选 anchor 文本作为锚点指令塞进 prompt。" +
                            "返回 runId,前端通过 get_run_status 轮询,DONE 后用 run.scriptId 跳新版。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "anchor", Map.of("type", "string", "description",
                                            "可选;塞进 user prompt 末尾的自由指令(如「主角改成女性」「钩子要更悬疑」)")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            String anchor = args.hasNonNull("anchor") ? args.get("anchor").asText() : null;
            Long runId = scriptService.regenerateInPlaceAsync(id, anchor, SOURCE);
            log.info("[Agent] regenerate_script id={} anchor={} → run={}", id, anchor != null, runId);
            return ok(runId, "脚本重生已发起,通常 30-60s。可轮询 get_run_status(runId) 查进度。");
        }
    }

    // -------- generate_storyboard --------
    private class GenerateStoryboard implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_storyboard",
                    "为某 script 生成分镜。force=true 时即使已有分镜也覆盖重生。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "force", Map.of("type", "boolean", "description", "默认 false;true 覆盖已有分镜")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            boolean force = args.hasNonNull("force") && args.get("force").asBoolean();
            Long runId = storyboardService.generateAsync(id, force, SOURCE);
            log.info("[Agent] generate_storyboard id={} force={} → run={}", id, force, runId);
            return ok(runId, "分镜生成已发起,通常 30-50s。可轮询 get_run_status(runId)。");
        }
    }

    // -------- generate_images --------
    private class GenerateImages implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_images",
                    "为某 script 的所有分镜批量出图。limit 可只测前 N 张省成本。" +
                            "成本敏感操作:每镜 1 次 LLM 调用,20-28 镜全跑 5-8 分钟 + 实打实出图费用。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "limit", Map.of("type", "integer", "description", "可选;只跑前 N 镜(测试用)"),
                                    "force", Map.of("type", "boolean", "description", "默认 false;true 覆盖已有 image_asset")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            Integer limit = args.hasNonNull("limit") ? args.get("limit").asInt() : null;
            boolean force = args.hasNonNull("force") && args.get("force").asBoolean();
            Long runId = imageGenService.generateForScriptAsync(id, force, limit, SOURCE);
            log.info("[Agent] generate_images id={} limit={} force={} → run={}", id, limit, force, runId);
            return ok(runId, "批量出图已发起,5-8 分钟 + 模型费用。get_run_status(runId) 看进度。");
        }
    }

    // -------- audit_images --------
    private class AuditImages implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "audit_images",
                    "对某 script 已生成的图片做一次美术审片(构图/手部/水印/锁脸一致性)。",
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
            Long runId = imageAuditService.auditScriptAsync(id, SOURCE);
            log.info("[Agent] audit_images id={} → run={}", id, runId);
            return ok(runId, "审图已发起,每张约 5-10s,合计 2-4 分钟。");
        }
    }

    // -------- regenerate_image_for_shot --------
    private class RegenerateImageForShot implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "regenerate_image_for_shot",
                    "重新生成单镜的图片。删该 shot 已有 asset → 重跑模型一次。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("shotId", Map.of("type", "integer")),
                            "required", List.of("shotId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long shotId = args.get("shotId").asLong();
            Long runId = imageGenService.regenerateForShotAsync(shotId, SOURCE);
            log.info("[Agent] regenerate_image_for_shot shotId={} → run={}", shotId, runId);
            return ok(runId, "单镜重生已发起,通常 10-30s。");
        }
    }

    // -------- generate_voice --------
    private class GenerateVoice implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_voice",
                    "合成旁白音频 + SRT 字幕。voiceModel 必填(如 'zh_female_qingxin');其他可空走默认。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "voiceModel", Map.of("type", "string", "description", "音色 id,如 zh_female_qingxin"),
                                    "voiceLabel", Map.of("type", "string"),
                                    "speed", Map.of("type", "number", "description", "语速倍率,默认 1.0"),
                                    "pitch", Map.of("type", "integer"),
                                    "subtitleStyle", Map.of("type", "string", "enum", List.of("standard", "highlight")),
                                    "markFinal", Map.of("type", "boolean", "description", "是否标为该 script 的 final voice")
                            ),
                            "required", List.of("scriptId", "voiceModel")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            String voiceModel = args.get("voiceModel").asText();
            String voiceLabel = args.hasNonNull("voiceLabel") ? args.get("voiceLabel").asText() : null;
            BigDecimal speed = args.hasNonNull("speed") ? new BigDecimal(args.get("speed").asText()) : null;
            Integer pitch = args.hasNonNull("pitch") ? args.get("pitch").asInt() : null;
            String subtitleStyle = args.hasNonNull("subtitleStyle") ? args.get("subtitleStyle").asText() : null;
            Boolean markFinal = args.hasNonNull("markFinal") ? args.get("markFinal").asBoolean() : null;
            VoiceGenService.GenParams p = new VoiceGenService.GenParams(
                    voiceModel, voiceLabel, speed, pitch, subtitleStyle, markFinal);
            Long runId = voiceGenService.generateAsync(id, p, SOURCE);
            log.info("[Agent] generate_voice id={} voice={} → run={}", id, voiceModel, runId);
            return ok(runId, "TTS 合成已发起,通常 20-40s(按字数)。");
        }
    }

    // -------- render_video --------
    private class RenderVideo implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "render_video",
                    "把 voice + 分镜图 + 字幕 + BGM 合成最终视频。" +
                            "重操作:CPU/磁盘消耗大,通常 3-10 分钟。voiceAssetId 不填走最新/final voice。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "voiceAssetId", Map.of("type", "integer", "description", "可选;不填后端自动选 final/最新 voice"),
                                    "format", Map.of("type", "string", "description", "如 9:16 / 16:9"),
                                    "width", Map.of("type", "integer"),
                                    "height", Map.of("type", "integer"),
                                    "markFinal", Map.of("type", "boolean")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            Long voiceAssetId = args.hasNonNull("voiceAssetId") ? args.get("voiceAssetId").asLong() : null;
            String format = args.hasNonNull("format") ? args.get("format").asText() : null;
            Integer width = args.hasNonNull("width") ? args.get("width").asInt() : null;
            Integer height = args.hasNonNull("height") ? args.get("height").asInt() : null;
            Boolean markFinal = args.hasNonNull("markFinal") ? args.get("markFinal").asBoolean() : null;
            VideoAssemblyService.RenderParams p = new VideoAssemblyService.RenderParams(
                    voiceAssetId, format, width, height, markFinal);
            Long runId = videoAssemblyService.renderAsync(id, p, SOURCE);
            log.info("[Agent] render_video id={} voiceAsset={} → run={}", id, voiceAssetId, runId);
            return ok(runId, "视频合成已发起,3-10 分钟。重操作。");
        }
    }

    // -------- generate_covers --------
    private class GenerateCovers implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_covers",
                    "生成视频封面图(多 ratio)。templateId 不填走品牌包默认。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "templateId", Map.of("type", "string"),
                                    "titleText", Map.of("type", "string"),
                                    "heroImageUrl", Map.of("type", "string", "description", "首图 URL,可选,后端会自动选 final 分镜图")
                            ),
                            "required", List.of("scriptId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("scriptId").asLong();
            String templateId = args.hasNonNull("templateId") ? args.get("templateId").asText() : null;
            String titleText = args.hasNonNull("titleText") ? args.get("titleText").asText() : null;
            String heroImageUrl = args.hasNonNull("heroImageUrl") ? args.get("heroImageUrl").asText() : null;
            CoverGenerationService.GenerateParams p = new CoverGenerationService.GenerateParams(
                    templateId, titleText, heroImageUrl);
            Long runId = coverGenerationService.generateAsync(id, p, SOURCE);
            log.info("[Agent] generate_covers id={} → run={}", id, runId);
            return ok(runId, "封面生成已发起,通常 30-60s。");
        }
    }

    // -------- run_factcheck --------
    private class RunFactCheck implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "run_factcheck",
                    "对某 script 跑事实核查(每个声明逐条 verify,3-5 分钟)。",
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
            Long runId = factCheckService.factCheckAsync(id, SOURCE);
            log.info("[Agent] run_factcheck id={} → run={}", id, runId);
            return ok(runId, "事实核查已发起,3-5 分钟。完成后调 GET /api/scripts/{id}/issues 看结果。");
        }
    }
}
