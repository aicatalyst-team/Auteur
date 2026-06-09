package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.llm.ChatRequest;
import com.auteur.preset.Preset;
import com.auteur.preset.PresetService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 预设写入工具。三件套:
 *
 *   - update_preset_field:        改一个字段,走 PresetService.update(覆盖当前版,不写 snapshot)
 *   - save_preset_as_new_version: 改 + 新写一份 snapshot,currentVersion+1(LLM 在做"重大改动"时用)
 *   - rollback_preset_version:    回到指定历史版本
 *
 * 字段白名单是受限子集:不让 LLM 改 visibility/owner_name 之类访问控制属性,
 * 也不让改 inputSchemaJson(影响输入校验,需要专门工作流)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresetWriteTools {

    private final ToolRegistry registry;
    private final PresetService presetService;

    /** 允许改的字段名(白名单)。把一个字段加进来需要在 applyField 同时支持。 */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "displayName",
            "description",
            "brainstormPromptYaml",
            "scriptPromptYaml",
            "scriptCriticPromptYaml",
            "scriptCriticThreshold",
            "storyboardPromptYaml",
            "storyboardMode",
            "assistantDirectorPromptYaml",
            "bgmMoodPromptYaml",
            "imageConfigJson",
            "voiceConfigJson",
            "compositionId",
            "formatWidth",
            "formatHeight",
            "watermarkText",
            "hookSegmentEnabled",
            "bgmEnabled",
            "bgmLocked",
            "minExtremeCloseup",
            "hookPageFlipSoundUrl"
    );

    @PostConstruct
    public void init() {
        registry.register(new UpdatePresetField());
        registry.register(new SavePresetAsNewVersion());
        registry.register(new RollbackPresetVersion());
    }

    /** 把 LLM 给的 newValue(JsonNode 任意类型)塞进 patch 实例的对应字段。 */
    private void applyField(Preset patch, String field, JsonNode v) {
        switch (field) {
            case "displayName" -> patch.setDisplayName(asText(v));
            case "description" -> patch.setDescription(asText(v));
            case "brainstormPromptYaml" -> patch.setBrainstormPromptYaml(asText(v));
            case "scriptPromptYaml" -> patch.setScriptPromptYaml(asText(v));
            case "scriptCriticPromptYaml" -> patch.setScriptCriticPromptYaml(asText(v));
            case "scriptCriticThreshold" -> patch.setScriptCriticThreshold(asInt(v));
            case "storyboardPromptYaml" -> patch.setStoryboardPromptYaml(asText(v));
            case "storyboardMode" -> patch.setStoryboardMode(requireEnum(v, List.of("PRECISE_BY_CUE", "FREE")));
            case "assistantDirectorPromptYaml" -> patch.setAssistantDirectorPromptYaml(asText(v));
            case "bgmMoodPromptYaml" -> patch.setBgmMoodPromptYaml(asText(v));
            case "imageConfigJson" -> patch.setImageConfigJson(asJsonText(v));
            case "voiceConfigJson" -> patch.setVoiceConfigJson(asJsonText(v));
            case "compositionId" -> patch.setCompositionId(asText(v));
            case "formatWidth" -> patch.setFormatWidth(asInt(v));
            case "formatHeight" -> patch.setFormatHeight(asInt(v));
            case "watermarkText" -> patch.setWatermarkText(asText(v));
            case "hookSegmentEnabled" -> patch.setHookSegmentEnabled(asBool(v));
            case "bgmEnabled" -> patch.setBgmEnabled(asBool(v));
            case "bgmLocked" -> patch.setBgmLocked(asBool(v));
            case "minExtremeCloseup" -> patch.setMinExtremeCloseup(asInt(v));
            case "hookPageFlipSoundUrl" -> patch.setHookPageFlipSoundUrl(asText(v));
            default -> throw new IllegalArgumentException("不支持的字段: " + field);
        }
    }

    /**
     * applyPatch 对 primitive boolean/int 字段无条件覆盖(因为没法用 null 区分"未设置"),
     * 单字段写入时如果不预填,会把这些字段清零。预填一次,applyField 再覆盖目标那一个。
     */
    private void prefillPrimitivesFromCurrent(Preset patch, Preset cur) {
        patch.setBgmEnabled(cur.isBgmEnabled());
        patch.setBgmLocked(cur.isBgmLocked());
        patch.setHookSegmentEnabled(cur.isHookSegmentEnabled());
        patch.setMinExtremeCloseup(cur.getMinExtremeCloseup());
    }

    private static String asText(JsonNode v) {
        if (v == null || v.isNull()) return null;
        return v.isTextual() ? v.asText() : v.toString();
    }

    /** 期望的是文本(JSON 字符串)。如果是对象/数组则序列化回去。 */
    private static String asJsonText(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isTextual()) return v.asText();
        return v.toString();
    }

    private static Integer asInt(JsonNode v) {
        if (v == null || v.isNull()) return null;
        if (v.isInt() || v.isLong()) return v.asInt();
        if (v.isTextual()) {
            try { return Integer.parseInt(v.asText().trim()); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("不是整数: " + v.asText()); }
        }
        throw new IllegalArgumentException("不是整数: " + v.toString());
    }

    private static boolean asBool(JsonNode v) {
        if (v == null || v.isNull()) return false;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isTextual()) return "true".equalsIgnoreCase(v.asText().trim()) || "1".equals(v.asText().trim());
        throw new IllegalArgumentException("不是布尔: " + v.toString());
    }

    private static String requireEnum(JsonNode v, List<String> allowed) {
        String s = asText(v);
        if (s == null || !allowed.contains(s)) {
            throw new IllegalArgumentException("枚举值必须是 " + allowed + ",当前: " + s);
        }
        return s;
    }

    private Map<String, Object> summary(Preset p, String oldVal, String newVal) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("presetId", p.getId());
        m.put("name", p.getName());
        m.put("currentVersion", p.getCurrentVersion());
        m.put("oldValuePreview", preview(oldVal));
        m.put("newValuePreview", preview(newVal));
        return m;
    }

    private static String preview(String s) {
        if (s == null) return null;
        if (s.length() <= 200) return s;
        return s.substring(0, 200) + "…(共" + s.length() + "字)";
    }

    private String currentValueAsString(Preset p, String field) {
        return switch (field) {
            case "displayName" -> p.getDisplayName();
            case "description" -> p.getDescription();
            case "brainstormPromptYaml" -> p.getBrainstormPromptYaml();
            case "scriptPromptYaml" -> p.getScriptPromptYaml();
            case "scriptCriticPromptYaml" -> p.getScriptCriticPromptYaml();
            case "scriptCriticThreshold" -> String.valueOf(p.getScriptCriticThreshold());
            case "storyboardPromptYaml" -> p.getStoryboardPromptYaml();
            case "storyboardMode" -> p.getStoryboardMode();
            case "assistantDirectorPromptYaml" -> p.getAssistantDirectorPromptYaml();
            case "bgmMoodPromptYaml" -> p.getBgmMoodPromptYaml();
            case "imageConfigJson" -> p.getImageConfigJson();
            case "voiceConfigJson" -> p.getVoiceConfigJson();
            case "compositionId" -> p.getCompositionId();
            case "formatWidth" -> String.valueOf(p.getFormatWidth());
            case "formatHeight" -> String.valueOf(p.getFormatHeight());
            case "watermarkText" -> p.getWatermarkText();
            case "hookSegmentEnabled" -> String.valueOf(p.isHookSegmentEnabled());
            case "bgmEnabled" -> String.valueOf(p.isBgmEnabled());
            case "bgmLocked" -> String.valueOf(p.isBgmLocked());
            case "minExtremeCloseup" -> String.valueOf(p.getMinExtremeCloseup());
            case "hookPageFlipSoundUrl" -> p.getHookPageFlipSoundUrl();
            default -> null;
        };
    }

    // ---- 共享 schema 片段 ----
    private static Map<String, Object> fieldsSchema() {
        return Map.of(
                "type", "string",
                "enum", ALLOWED_FIELDS.stream().sorted().toList(),
                "description", "要修改的字段名"
        );
    }

    private static Map<String, Object> newValueSchema() {
        return Map.of(
                "description", "新值。字符串/数字/布尔/JSON 对象,后端按 field 类型解析。" +
                        "对 *PromptYaml 字段直接传 yaml 文本;对 imageConfigJson/voiceConfigJson 传 JSON 文本或对象。"
        );
    }

    // -------- update_preset_field --------
    private class UpdatePresetField implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "presetId", Map.of("type", "integer", "description", "preset.id"),
                            "field", fieldsSchema(),
                            "newValue", newValueSchema()
                    ),
                    "required", List.of("presetId", "field", "newValue")
            );
            return ChatRequest.Tool.of(
                    "update_preset_field",
                    "修改某预设的某字段(覆盖当前版,不写 snapshot)。" +
                            "适合小调整(改阈值/水印文案等)。重大改动用 save_preset_as_new_version。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("presetId").asLong();
            String field = args.get("field").asText();
            JsonNode newValue = args.get("newValue");
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("字段不在白名单: " + field);
            }
            Preset cur = presetService.get(id);
            String oldVal = currentValueAsString(cur, field);

            Preset patch = new Preset();
            prefillPrimitivesFromCurrent(patch, cur);
            applyField(patch, field, newValue);
            Preset updated = presetService.update(id, patch);
            String newVal = currentValueAsString(updated, field);

            log.info("[Agent] update_preset_field id={} field={} → ok", id, field);
            return summary(updated, oldVal, newVal);
        }
    }

    private class SavePresetAsNewVersion implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "presetId", Map.of("type", "integer"),
                            "field", fieldsSchema(),
                            "newValue", newValueSchema(),
                            "comment", Map.of(
                                    "type", "string",
                                    "description", "本次修改的备注(写入 preset_version.comment,方便日后回滚定位)"
                            )
                    ),
                    "required", List.of("presetId", "field", "newValue")
            );
            return ChatRequest.Tool.of(
                    "save_preset_as_new_version",
                    "改一个字段并写一份 snapshot,currentVersion+1。" +
                            "适合改 prompt yaml / image_config_json 等重大改动,确保可回滚。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("presetId").asLong();
            String field = args.get("field").asText();
            JsonNode newValue = args.get("newValue");
            String comment = args.hasNonNull("comment") ? args.get("comment").asText() : null;
            if (!ALLOWED_FIELDS.contains(field)) {
                throw new IllegalArgumentException("字段不在白名单: " + field);
            }
            Preset cur = presetService.get(id);
            String oldVal = currentValueAsString(cur, field);

            Preset patch = new Preset();
            prefillPrimitivesFromCurrent(patch, cur);
            applyField(patch, field, newValue);
            Preset updated = presetService.saveAsNewVersion(id, patch, comment);
            String newVal = currentValueAsString(updated, field);

            log.info("[Agent] save_preset_as_new_version id={} field={} → v{}", id, field, updated.getCurrentVersion());
            Map<String, Object> out = summary(updated, oldVal, newVal);
            out.put("savedAsVersion", updated.getCurrentVersion());
            out.put("comment", comment);
            return out;
        }
    }

    private class RollbackPresetVersion implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "presetId", Map.of("type", "integer"),
                            "version", Map.of("type", "integer", "description", "目标历史版本号(可用 list_preset_versions 查)")
                    ),
                    "required", List.of("presetId", "version")
            );
            return ChatRequest.Tool.of(
                    "rollback_preset_version",
                    "回滚某预设到指定历史版本。回滚本身也会写一份 snapshot,可继续往前回。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("presetId").asLong();
            int version = args.get("version").asInt();
            Preset rolled = presetService.rollback(id, version);
            log.info("[Agent] rollback_preset_version id={} → v{}", id, rolled.getCurrentVersion());
            return Map.of(
                    "ok", true,
                    "presetId", rolled.getId(),
                    "name", rolled.getName(),
                    "currentVersion", rolled.getCurrentVersion(),
                    "rolledBackTo", version
            );
        }
    }
}
