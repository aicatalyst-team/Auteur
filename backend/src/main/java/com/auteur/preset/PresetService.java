package com.auteur.preset;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Preset CRUD + 版本管理。
 *
 *   - update() 只覆盖当前版,不自动写 snapshot
 *   - saveAsNewVersion() 先 snapshot 当前 preset 到 preset_version,然后 currentVersion+1 + apply
 *   - rollback() 拿 snapshot_json 反序列化覆盖 preset 字段(不再写新 snapshot,避免循环)
 *   - imageConfig() / voiceConfig() 把 JSON 列解析成强类型
 *
 * visibility/owner_name 是软标记,无鉴权。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetService {

    private final PresetRepository presetRepo;
    private final PresetVersionRepository versionRepo;
    private final PresetAssetRepository assetRepo;
    private final ObjectMapper objectMapper;

    public Preset get(Long id) {
        return presetRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "preset id=" + id + " 不存在"));
    }

    public Preset getByName(String name) {
        return presetRepo.findByName(name)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "preset name=" + name + " 不存在"));
    }

    public Optional<Preset> findByName(String name) {
        return presetRepo.findByName(name);
    }

    public List<Preset> listVisible(boolean adminMode, String ownerName) {
        if (adminMode && ownerName != null && !ownerName.isBlank()) {
            return presetRepo.findByOwnerNameOrVisibilityOrderByUpdatedAtDesc(ownerName, "public");
        }
        return presetRepo.findByVisibilityOrderByUpdatedAtDesc("public");
    }

    /** 解析 image_config_json 为强类型;空字符串/null 返回空 ImageConfig。 */
    public ImageConfig imageConfig(Preset preset) {
        String json = preset.getImageConfigJson();
        if (json == null || json.isBlank()) return new ImageConfig();
        try {
            return objectMapper.readValue(json, ImageConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("[Preset] image_config_json 解析失败 presetId={}: {}", preset.getId(), e.toString());
            return new ImageConfig();
        }
    }

    /** voice_config_json 整个 null/blank → 返回 null,表示"该预设无旁白"。 */
    public VoiceConfig voiceConfig(Preset preset) {
        String json = preset.getVoiceConfigJson();
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, VoiceConfig.class);
        } catch (JsonProcessingException e) {
            log.warn("[Preset] voice_config_json 解析失败 presetId={}: {}", preset.getId(), e.toString());
            return null;
        }
    }

    @Transactional
    public Preset create(Preset draft) {
        if (draft.getName() == null || draft.getName().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "preset.name 不能为空");
        }
        if (presetRepo.existsByName(draft.getName())) {
            throw new ResponseStatusException(BAD_REQUEST, "preset name=" + draft.getName() + " 已存在");
        }
        validateRequiredFields(draft);
        draft.setId(null);
        draft.setCurrentVersion(1);
        Preset saved = presetRepo.save(draft);
        snapshotCurrent(saved, "init");
        log.info("[Preset] created id={} name={}", saved.getId(), saved.getName());
        return saved;
    }

    /** 覆盖当前版本(不写 snapshot)。要保留历史用 saveAsNewVersion。 */
    @Transactional
    public Preset update(Long id, Preset patch) {
        Preset cur = get(id);
        applyPatch(cur, patch);
        validateRequiredFields(cur);
        return presetRepo.save(cur);
    }

    /** 先 snapshot 当前内容,再 currentVersion+1 + 写入新值。 */
    @Transactional
    public Preset saveAsNewVersion(Long id, Preset patch, String comment) {
        Preset cur = get(id);
        snapshotCurrent(cur, comment);
        applyPatch(cur, patch);
        cur.setCurrentVersion(cur.getCurrentVersion() + 1);
        validateRequiredFields(cur);
        Preset saved = presetRepo.save(cur);
        log.info("[Preset] saveAsNewVersion id={} → v{}", id, saved.getCurrentVersion());
        return saved;
    }

    @Transactional
    public Preset rollback(Long id, Integer targetVersion) {
        PresetVersion snap = versionRepo.findByPresetIdAndVersion(id, targetVersion)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND,
                        "preset id=" + id + " 没有 v" + targetVersion + " 快照"));
        try {
            Preset snapshot = objectMapper.readValue(snap.getSnapshotJson(), Preset.class);
            Preset cur = get(id);
            // 回滚也是一次"重大改动",先存当前为新 snapshot
            snapshotCurrent(cur, "auto: before rollback to v" + targetVersion);
            applyPatch(cur, snapshot);
            cur.setCurrentVersion(cur.getCurrentVersion() + 1);
            return presetRepo.save(cur);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(BAD_REQUEST, "v" + targetVersion + " snapshot 损坏: " + e.toString());
        }
    }

    @Transactional
    public void delete(Long id) {
        Preset preset = get(id);
        // FK ON DELETE CASCADE 会把 preset_version / preset_asset 一起清掉
        presetRepo.delete(preset);
        log.info("[Preset] deleted id={} name={}", id, preset.getName());
    }

    public List<PresetVersion> listVersions(Long id) {
        return versionRepo.findByPresetIdOrderByVersionDesc(id);
    }

    public List<PresetAsset> listAssets(Long id) {
        return assetRepo.findByPresetId(id);
    }

    private void snapshotCurrent(Preset preset, String comment) {
        try {
            String json = objectMapper.writeValueAsString(preset);
            PresetVersion snap = new PresetVersion();
            snap.setPresetId(preset.getId());
            snap.setVersion(preset.getCurrentVersion());
            snap.setSnapshotJson(json);
            snap.setComment(comment);
            versionRepo.save(snap);
        } catch (JsonProcessingException e) {
            // snapshot 失败不阻塞主流程,只 warn
            log.warn("[Preset] snapshot 失败 presetId={}: {}", preset.getId(), e.toString());
        }
    }

    /** patch 里非 null 的字段覆盖 cur,null 字段保留 cur 原值。 */
    private void applyPatch(Preset cur, Preset patch) {
        if (patch.getDisplayName() != null) cur.setDisplayName(patch.getDisplayName());
        if (patch.getDescription() != null) cur.setDescription(patch.getDescription());
        if (patch.getVisibility() != null) cur.setVisibility(patch.getVisibility());
        if (patch.getOwnerName() != null) cur.setOwnerName(patch.getOwnerName());
        if (patch.getInputSchemaJson() != null) cur.setInputSchemaJson(patch.getInputSchemaJson());
        if (patch.getBrainstormPromptYaml() != null) cur.setBrainstormPromptYaml(patch.getBrainstormPromptYaml());
        if (patch.getScriptPromptYaml() != null) cur.setScriptPromptYaml(patch.getScriptPromptYaml());
        if (patch.getScriptCriticPromptYaml() != null) cur.setScriptCriticPromptYaml(patch.getScriptCriticPromptYaml());
        if (patch.getScriptCriticThreshold() != null) cur.setScriptCriticThreshold(patch.getScriptCriticThreshold());
        if (patch.getStoryboardPromptYaml() != null) cur.setStoryboardPromptYaml(patch.getStoryboardPromptYaml());
        if (patch.getStoryboardMode() != null) cur.setStoryboardMode(patch.getStoryboardMode());
        if (patch.getAssistantDirectorPromptYaml() != null) cur.setAssistantDirectorPromptYaml(patch.getAssistantDirectorPromptYaml());
        if (patch.getBgmMoodPromptYaml() != null) cur.setBgmMoodPromptYaml(patch.getBgmMoodPromptYaml());
        if (patch.getImageConfigJson() != null) cur.setImageConfigJson(patch.getImageConfigJson());
        if (patch.getVoiceConfigJson() != null) cur.setVoiceConfigJson(patch.getVoiceConfigJson());
        // primitive boolean/int 字段没法用 null 做"未设置"标记。约定:调用方要么传完整对象(UI),
        // 要么先用 cur 的当前值预填 patch(Agent 单字段写入路径),applyPatch 一律无条件覆盖。
        cur.setBgmEnabled(patch.isBgmEnabled());
        cur.setBgmLocked(patch.isBgmLocked());
        cur.setHookSegmentEnabled(patch.isHookSegmentEnabled());
        cur.setMinExtremeCloseup(patch.getMinExtremeCloseup());
        if (patch.getCompositionId() != null) cur.setCompositionId(patch.getCompositionId());
        if (patch.getFormatWidth() != null) cur.setFormatWidth(patch.getFormatWidth());
        if (patch.getFormatHeight() != null) cur.setFormatHeight(patch.getFormatHeight());
        if (patch.getWatermarkText() != null) cur.setWatermarkText(patch.getWatermarkText());
        if (patch.getHookPageFlipSoundUrl() != null) cur.setHookPageFlipSoundUrl(patch.getHookPageFlipSoundUrl());
    }

    private void validateRequiredFields(Preset preset) {
        if (preset.getScriptPromptYaml() == null || preset.getScriptPromptYaml().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "script_prompt_yaml 必填");
        }
        if (preset.getStoryboardPromptYaml() == null || preset.getStoryboardPromptYaml().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "storyboard_prompt_yaml 必填");
        }
        if (preset.getCompositionId() == null || preset.getCompositionId().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "composition_id 必填");
        }
        if (preset.getFormatWidth() == null || preset.getFormatHeight() == null) {
            throw new ResponseStatusException(BAD_REQUEST, "format_width / format_height 必填");
        }
    }
}
