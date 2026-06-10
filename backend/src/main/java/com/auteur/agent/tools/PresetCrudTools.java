package com.auteur.agent.tools;

import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
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

/**
 * 预设 CRUD 工具:create / delete / duplicate。
 *
 * 圈一只覆盖了"修改已有预设字段",CRUD 操作前端"新建预设""复制""删除"按钮没对应工具,
 * 用户从零起步必须去网页 UI。
 *
 * duplicate_preset 是"先 GET 再 POST"的语义糖,LLM 自己也能拼,但工具内置一次性完成更短。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresetCrudTools {

    private final ToolRegistry registry;
    private final PresetService presetService;

    @PostConstruct
    public void init() {
        registry.register(new CreatePreset());
        registry.register(new DeletePreset());
        registry.register(new DuplicatePreset());
    }

    /** 接受 LLM 给的字段 map,组装成 Preset 实体。约束跟 PresetService.create 一致。 */
    private Preset buildDraft(JsonNode args) {
        Preset p = new Preset();
        p.setName(args.get("name").asText());
        if (args.hasNonNull("displayName")) p.setDisplayName(args.get("displayName").asText());
        if (args.hasNonNull("description")) p.setDescription(args.get("description").asText());
        p.setVisibility(args.hasNonNull("visibility") ? args.get("visibility").asText() : "private");
        if (args.hasNonNull("ownerName")) p.setOwnerName(args.get("ownerName").asText());
        if (args.hasNonNull("brainstormPromptYaml")) p.setBrainstormPromptYaml(args.get("brainstormPromptYaml").asText());
        p.setScriptPromptYaml(args.get("scriptPromptYaml").asText());
        if (args.hasNonNull("scriptCriticPromptYaml")) p.setScriptCriticPromptYaml(args.get("scriptCriticPromptYaml").asText());
        if (args.hasNonNull("scriptCriticThreshold")) p.setScriptCriticThreshold(args.get("scriptCriticThreshold").asInt());
        p.setStoryboardPromptYaml(args.get("storyboardPromptYaml").asText());
        p.setStoryboardMode(args.hasNonNull("storyboardMode") ? args.get("storyboardMode").asText() : "FREE");
        if (args.hasNonNull("assistantDirectorPromptYaml")) p.setAssistantDirectorPromptYaml(args.get("assistantDirectorPromptYaml").asText());
        if (args.hasNonNull("bgmMoodPromptYaml")) p.setBgmMoodPromptYaml(args.get("bgmMoodPromptYaml").asText());
        if (args.hasNonNull("imageConfigJson")) {
            JsonNode v = args.get("imageConfigJson");
            p.setImageConfigJson(v.isTextual() ? v.asText() : v.toString());
        }
        if (args.hasNonNull("voiceConfigJson")) {
            JsonNode v = args.get("voiceConfigJson");
            p.setVoiceConfigJson(v.isTextual() ? v.asText() : v.toString());
        }
        p.setBgmEnabled(args.hasNonNull("bgmEnabled") && args.get("bgmEnabled").asBoolean());
        p.setBgmLocked(args.hasNonNull("bgmLocked") && args.get("bgmLocked").asBoolean());
        p.setHookSegmentEnabled(args.hasNonNull("hookSegmentEnabled") && args.get("hookSegmentEnabled").asBoolean());
        p.setCompositionId(args.get("compositionId").asText());
        p.setFormatWidth(args.get("formatWidth").asInt());
        p.setFormatHeight(args.get("formatHeight").asInt());
        if (args.hasNonNull("watermarkText")) p.setWatermarkText(args.get("watermarkText").asText());
        if (args.hasNonNull("hookPageFlipSoundUrl")) p.setHookPageFlipSoundUrl(args.get("hookPageFlipSoundUrl").asText());
        if (args.hasNonNull("minExtremeCloseup")) p.setMinExtremeCloseup(args.get("minExtremeCloseup").asInt());
        return p;
    }

    private Map<String, Object> summary(Preset p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("displayName", p.getDisplayName());
        m.put("currentVersion", p.getCurrentVersion());
        return m;
    }

    private class CreatePreset implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            // schema 列出"必填"加部分关键可选;LLM 看到 description 知道全字段集合。
            return ChatRequest.Tool.of(
                    "create_preset",
                    "新建预设。必填:name(全局唯一,如 my_lifecopy)、scriptPromptYaml、storyboardPromptYaml、" +
                            "compositionId、formatWidth、formatHeight。其他字段可空,行为详见 PresetEditor 的字段说明。" +
                            "建议先 get_preset_by_name(name=freeform) 拿一份基础模板再改。",
                    Map.of(
                            "type", "object",
                            "properties", Map.ofEntries(
                                    Map.entry("name", Map.of("type", "string", "description", "全局唯一 key,如 my_preset")),
                                    Map.entry("displayName", Map.of("type", "string")),
                                    Map.entry("description", Map.of("type", "string")),
                                    Map.entry("visibility", Map.of("type", "string", "enum", List.of("public", "private"))),
                                    Map.entry("ownerName", Map.of("type", "string")),
                                    Map.entry("brainstormPromptYaml", Map.of("type", "string")),
                                    Map.entry("scriptPromptYaml", Map.of("type", "string")),
                                    Map.entry("scriptCriticPromptYaml", Map.of("type", "string")),
                                    Map.entry("scriptCriticThreshold", Map.of("type", "integer")),
                                    Map.entry("storyboardPromptYaml", Map.of("type", "string")),
                                    Map.entry("storyboardMode", Map.of("type", "string", "enum", List.of("PRECISE_BY_CUE", "FREE"))),
                                    Map.entry("imageConfigJson", Map.of("description", "JSON 文本或对象")),
                                    Map.entry("voiceConfigJson", Map.of("description", "JSON 文本或对象;null 表示无旁白")),
                                    Map.entry("compositionId", Map.of("type", "string")),
                                    Map.entry("formatWidth", Map.of("type", "integer")),
                                    Map.entry("formatHeight", Map.of("type", "integer")),
                                    Map.entry("watermarkText", Map.of("type", "string")),
                                    Map.entry("bgmEnabled", Map.of("type", "boolean")),
                                    Map.entry("bgmLocked", Map.of("type", "boolean")),
                                    Map.entry("hookSegmentEnabled", Map.of("type", "boolean"))
                            ),
                            "required", List.of("name", "scriptPromptYaml", "storyboardPromptYaml",
                                    "compositionId", "formatWidth", "formatHeight")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            Preset draft = buildDraft(args);
            Preset saved = presetService.create(draft);
            log.info("[Agent] create_preset name={} → id={}", saved.getName(), saved.getId());
            return summary(saved);
        }
    }

    private class DeletePreset implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "delete_preset",
                    "删除预设(连带级联清理 preset_version 和 preset_asset)。" +
                            "不可恢复 — 调用前务必跟用户确认 preset.id 和 displayName。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("presetId", Map.of("type", "integer")),
                            "required", List.of("presetId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("presetId").asLong();
            Preset p = presetService.get(id); // 让 service 抛 404 + 用作 summary
            presetService.delete(id);
            log.info("[Agent] delete_preset id={} name={}", id, p.getName());
            return Map.of("ok", true, "id", id, "name", p.getName());
        }
    }

    private class DuplicatePreset implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "duplicate_preset",
                    "复制现有预设作为新行(name 必须新)。所有字段照抄,version 重置为 1。" +
                            "适合在已有预设基础上做实验性变体,不影响原行。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "sourceId", Map.of("type", "integer", "description", "源预设 id"),
                                    "newName", Map.of("type", "string", "description", "新预设的 name(全局唯一)"),
                                    "newDisplayName", Map.of("type", "string", "description", "可选新显示名,默认沿用源名加 '副本'")
                            ),
                            "required", List.of("sourceId", "newName")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long sourceId = args.get("sourceId").asLong();
            String newName = args.get("newName").asText();
            String newDisplayName = args.hasNonNull("newDisplayName") ? args.get("newDisplayName").asText() : null;

            Preset src = presetService.get(sourceId);
            Preset copy = new Preset();
            // 字段照抄,但 id/name/version/timestamps 独立
            copy.setName(newName);
            copy.setDisplayName(newDisplayName != null ? newDisplayName
                    : (src.getDisplayName() == null ? newName : src.getDisplayName() + " 副本"));
            copy.setDescription(src.getDescription());
            copy.setVisibility(src.getVisibility());
            copy.setOwnerName(src.getOwnerName());
            copy.setInputSchemaJson(src.getInputSchemaJson());
            copy.setBrainstormPromptYaml(src.getBrainstormPromptYaml());
            copy.setScriptPromptYaml(src.getScriptPromptYaml());
            copy.setScriptCriticPromptYaml(src.getScriptCriticPromptYaml());
            copy.setScriptCriticThreshold(src.getScriptCriticThreshold());
            copy.setStoryboardPromptYaml(src.getStoryboardPromptYaml());
            copy.setStoryboardMode(src.getStoryboardMode());
            copy.setAssistantDirectorPromptYaml(src.getAssistantDirectorPromptYaml());
            copy.setBgmMoodPromptYaml(src.getBgmMoodPromptYaml());
            copy.setImageConfigJson(src.getImageConfigJson());
            copy.setVoiceConfigJson(src.getVoiceConfigJson());
            copy.setBgmEnabled(src.isBgmEnabled());
            copy.setBgmLocked(src.isBgmLocked());
            copy.setCompositionId(src.getCompositionId());
            copy.setFormatWidth(src.getFormatWidth());
            copy.setFormatHeight(src.getFormatHeight());
            copy.setWatermarkText(src.getWatermarkText());
            copy.setHookSegmentEnabled(src.isHookSegmentEnabled());
            copy.setHookPageFlipSoundUrl(src.getHookPageFlipSoundUrl());
            copy.setMinExtremeCloseup(src.getMinExtremeCloseup());

            Preset saved = presetService.create(copy);
            log.info("[Agent] duplicate_preset srcId={} → newId={} newName={}",
                    sourceId, saved.getId(), saved.getName());
            return summary(saved);
        }
    }
}
