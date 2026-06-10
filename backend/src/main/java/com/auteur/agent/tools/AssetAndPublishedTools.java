package com.auteur.agent.tools;

import com.auteur.agent.ActionToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.bgm.BgmService;
import com.auteur.domain.PublishedVideo;
import com.auteur.domain.PublishedVideoRepository;
import com.auteur.image.ImageAuditService;
import com.auteur.llm.ChatRequest;
import com.auteur.published.PublishedVideoUpsertRequest;
import com.auteur.published.PublishedVideoUpsertService;
import com.auteur.voice.VoiceDemoService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * P2 - 资产/发布/试听 工具集:
 *
 *   - recommend_bgm           (ACTION) 拉 Jamendo 候选曲(可能调外部 API)
 *   - select_bgm              (WRITE)  选定曲并下载到本地(写 script_bgm_choice + 文件)
 *   - audit_image_asset       (ACTION) 单图重审(异步,返 runId)
 *   - generate_voice_demo     (ACTION) 试听 voice 音色(可能合成新 mp3 缓存)
 *   - create_published_video  (WRITE)  录入已发布视频
 *   - bulk_create_published_videos (WRITE) 批量录入(merge 语义,不整批 rollback)
 *   - delete_published_video  (WRITE)  删已发布视频
 *
 * 多数是单 entity save 或调现有 @Transactional 的 service,不需要外层事务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssetAndPublishedTools {

    private final ToolRegistry registry;
    private final BgmService bgmService;
    private final ImageAuditService imageAuditService;
    private final VoiceDemoService voiceDemoService;
    private final PublishedVideoRepository publishedRepo;
    private final PublishedVideoUpsertService upsertService;

    @PostConstruct
    public void init() {
        registry.register(new RecommendBgm());
        registry.register(new SelectBgm());
        registry.register(new AuditImageAsset());
        registry.register(new GenerateVoiceDemo());
        registry.register(new CreatePublishedVideo());
        registry.register(new BulkCreatePublishedVideos());
        registry.register(new DeletePublishedVideo());
    }

    // ============ recommend_bgm ============
    private class RecommendBgm implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "recommend_bgm",
                    "为某 script 推荐 BGM 候选(基于脚本情绪走 Jamendo API)。返回 BgmTrackDto 列表。" +
                            "调外部 API,需要 jamendo client_id 配置;可能 SERVICE_UNAVAILABLE 失败。",
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
            List<BgmService.BgmTrackDto> tracks = bgmService.recommend(id);
            log.info("[Agent] recommend_bgm scriptId={} → {} tracks", id, tracks.size());
            return Map.of("scriptId", id, "count", tracks.size(), "tracks", tracks);
        }
    }

    // ============ select_bgm ============
    private class SelectBgm implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "select_bgm",
                    "为某 script 选定 BGM 曲目(写 script_bgm_choice;若曲目未下载会触发下载到本地)。" +
                            "volume 范围 0.05-0.60,默认 0.3。注意:bgmLocked 预设由后端兜底选曲,本工具仍可手动覆盖。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "bgmTrackId", Map.of("type", "integer", "description", "bgm_track.id;先用 recommend_bgm 拿候选"),
                                    "volume", Map.of("type", "number", "description", "0.05-0.60,默认 0.3")
                            ),
                            "required", List.of("scriptId", "bgmTrackId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long scriptId = args.get("scriptId").asLong();
            long trackId = args.get("bgmTrackId").asLong();
            BigDecimal volume = args.hasNonNull("volume")
                    ? new BigDecimal(args.get("volume").asText())
                    : new BigDecimal("0.3");
            BgmService.ChoiceDto choice = bgmService.select(scriptId, trackId, volume);
            log.info("[Agent] select_bgm scriptId={} trackId={}", scriptId, trackId);
            return choice;
        }
    }

    // ============ audit_image_asset ============
    private class AuditImageAsset implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "audit_image_asset",
                    "对单张图片做美术审片(构图/手部/水印/锁脸一致性等)。异步,立即返 runId。" +
                            "区别于 audit_images:本工具针对一张 asset,后者跑整个 script 的所有图。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("assetId", Map.of("type", "integer")),
                            "required", List.of("assetId")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("assetId").asLong();
            Long runId = imageAuditService.auditAssetAsync(id, "agent");
            log.info("[Agent] audit_image_asset assetId={} → run={}", id, runId);
            return Map.of("ok", true, "runId", runId, "hint", "审图已发起,通常 5-10s。");
        }
    }

    // ============ generate_voice_demo ============
    private class GenerateVoiceDemo implements ActionToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "generate_voice_demo",
                    "试听某 voice 音色(同 voiceType+speed 命中本地 mp3 缓存零成本;不命中才合成新音频)。" +
                            "返回 audioUrl 给前端播放;前端 VoiceCatalog 用此预览。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "voiceType", Map.of("type", "string", "description", ""),
                                    "speed", Map.of("type", "number", "description", "默认 1.0")
                            ),
                            "required", List.of("voiceType")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            String voiceType = args.get("voiceType").asText();
            Double speed = args.hasNonNull("speed") ? args.get("speed").asDouble() : 1.0;
            String url = voiceDemoService.getDemoUrl(voiceType, speed);
            log.info("[Agent] generate_voice_demo voiceType={} speed={}", voiceType, speed);
            return Map.of("audioUrl", url, "voiceType", voiceType, "speed", speed);
        }
    }

    // ============ create_published_video ============
    private class CreatePublishedVideo implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "create_published_video",
                    "录入一条已发布视频(用户在抖音/快手发完手动登记进 Auteur 做归因)。" +
                            "title/platform/publishedAt 必填;同 platform+platformVideoId 已存在会 409。",
                    Map.of(
                            "type", "object",
                            "properties", Map.ofEntries(
                                    Map.entry("title", Map.of("type", "string")),
                                    Map.entry("platform", Map.of("type", "string", "description", "douyin / kuaishou 等")),
                                    Map.entry("publishedAt", Map.of("type", "string",
                                            "description", "ISO 时间戳,如 2024-03-15T10:30:00")),
                                    Map.entry("platformVideoId", Map.of("type", "string", "description", "平台 vid,可空")),
                                    Map.entry("scriptId", Map.of("type", "integer")),
                                    Map.entry("topicId", Map.of("type", "integer")),
                                    Map.entry("projectName", Map.of("type", "string")),
                                    Map.entry("durationSeconds", Map.of("type", "integer")),
                                    Map.entry("views", Map.of("type", "integer")),
                                    Map.entry("likes", Map.of("type", "integer")),
                                    Map.entry("comments", Map.of("type", "integer")),
                                    Map.entry("shares", Map.of("type", "integer"))
                            ),
                            "required", List.of("title", "platform", "publishedAt")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            PublishedVideoUpsertRequest req = buildRequest(args);
            if (req.platformVideoId() != null && !req.platformVideoId().isBlank()) {
                publishedRepo.findByPlatformAndPlatformVideoId(req.platform(), req.platformVideoId())
                        .ifPresent(existing -> {
                            throw new ResponseStatusException(CONFLICT,
                                    "同平台 vid 已存在 (id=" + existing.getId() + ")");
                        });
            }
            PublishedVideo v = upsertService.applyUpsert(new PublishedVideo(), req);
            PublishedVideo saved = publishedRepo.save(v);
            log.info("[Agent] create_published_video id={} platform={}", saved.getId(), saved.getPlatform());
            return Map.of(
                    "ok", true,
                    "id", saved.getId(),
                    "platform", saved.getPlatform(),
                    "title", saved.getTitle(),
                    "publishedAt", saved.getPublishedAt() == null ? null : saved.getPublishedAt().toString()
            );
        }
    }

    private PublishedVideoUpsertRequest buildRequest(JsonNode args) {
        // PublishedVideoUpsertRequest 是 31 字段 record;agent 端只暴露最常用 12 个,其余字段后续从平台抓取/扩展再加。
        return new PublishedVideoUpsertRequest(
                args.hasNonNull("scriptId") ? args.get("scriptId").asLong() : null,
                args.hasNonNull("topicId") ? args.get("topicId").asLong() : null,
                args.get("title").asText(),
                args.hasNonNull("projectName") ? args.get("projectName").asText() : null,
                args.get("platform").asText(),
                args.hasNonNull("platformVideoId") ? args.get("platformVideoId").asText() : null,
                LocalDateTime.parse(args.get("publishedAt").asText()),
                args.hasNonNull("durationSeconds") ? args.get("durationSeconds").asInt() : null,
                args.hasNonNull("views") ? args.get("views").asLong() : null,
                args.hasNonNull("likes") ? args.get("likes").asLong() : null,
                args.hasNonNull("comments") ? args.get("comments").asLong() : null,
                args.hasNonNull("shares") ? args.get("shares").asLong() : null,
                // 13-31:retentionPct/avgPlaySeconds/drop2sPct/play5sPct/avgPlayRatioPct/favoriteRatePct/
                //         dislikeRatePct/hookCtr/costYuan/hookTemplate/notes/coverUrl/likeRate/shareRate/
                //         commentRate/subscribeCount/unsubscribeCount/coverCtr/homepageVisitCount
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null
        );
    }

    // ============ bulk_create_published_videos ============
    private class BulkCreatePublishedVideos implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "bulk_create_published_videos",
                    "批量录入已发布视频。merge 语义:存在则更新,不存在则插。某行错了塞 errors 不整批 rollback。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "rows", Map.of("type", "array",
                                            "description", "数组,每项跟 create_published_video 的字段一致(title/platform/publishedAt 必填)",
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
                    PublishedVideoUpsertRequest req = buildRequest(row);
                    PublishedVideoUpsertService.UpsertOutcome out = upsertService.upsert(req);
                    if (out.result() == PublishedVideoUpsertService.Result.INSERTED) inserted++;
                    else updated++;
                } catch (Exception e) {
                    skipped++;
                    errors.add("row " + i + ": " + e.getMessage());
                }
            }
            log.info("[Agent] bulk_create_published_videos inserted={} updated={} skipped={}", inserted, updated, skipped);
            return Map.of("inserted", inserted, "updated", updated, "skipped", skipped, "errors", errors);
        }
    }

    // ============ delete_published_video ============
    private class DeletePublishedVideo implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_published_video",
                    "删除一条已发布视频记录(只删 Auteur 内的归档,不影响平台上的视频)。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("id", Map.of("type", "integer", "description", "published_video.id")),
                            "required", List.of("id")
                    )
            );
        }
        @Override
        public Object execute(JsonNode args) {
            long id = args.get("id").asLong();
            if (!publishedRepo.existsById(id)) {
                throw new ResponseStatusException(NOT_FOUND, "已发布视频 " + id + " 不存在");
            }
            publishedRepo.deleteById(id);
            log.info("[Agent] delete_published_video id={}", id);
            return Map.of("ok", true, "id", id);
        }
    }
}
