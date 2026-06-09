package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.llm.ChatRequest;
import com.auteur.preset.Preset;
import com.auteur.preset.PresetService;
import com.auteur.preset.PresetVersion;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 预设只读工具集。
 *
 * - list_presets:        摘要列表(不返 prompt yaml 等大字段,LLM 想看详情再调 get_preset)
 * - get_preset:          按 id 取完整字段(yaml 过长会截断成 first 4000 字 + 标注)
 * - get_preset_by_name:  按 name 取
 * - list_preset_versions: 历史版本列表
 *
 * 全部走 admin 视角(adminMode=true, ownerName=null) — Agent 控制台默认拥有完整可见性。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PresetReadTools {

    private static final int MAX_YAML_RETURN = 4000;

    private final ToolRegistry registry;
    private final PresetService presetService;

    @PostConstruct
    public void init() {
        registry.register(new ListPresets());
        registry.register(new GetPreset());
        registry.register(new GetPresetByName());
        registry.register(new ListPresetVersions());
    }

    /** 摘要,避免 yaml/JSON 占满 LLM 上下文。 */
    private Map<String, Object> summarize(Preset p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("displayName", p.getDisplayName());
        m.put("description", p.getDescription());
        m.put("visibility", p.getVisibility());
        m.put("ownerName", p.getOwnerName());
        m.put("storyboardMode", p.getStoryboardMode());
        m.put("scriptCriticThreshold", p.getScriptCriticThreshold());
        m.put("compositionId", p.getCompositionId());
        m.put("formatWidth", p.getFormatWidth());
        m.put("formatHeight", p.getFormatHeight());
        m.put("watermarkText", p.getWatermarkText());
        m.put("hookSegmentEnabled", p.isHookSegmentEnabled());
        m.put("bgmEnabled", p.isBgmEnabled());
        m.put("bgmLocked", p.isBgmLocked());
        m.put("minExtremeCloseup", p.getMinExtremeCloseup());
        m.put("currentVersion", p.getCurrentVersion());
        m.put("updatedAt", p.getUpdatedAt() == null ? null : p.getUpdatedAt().toString());
        return m;
    }

    private Map<String, Object> detail(Preset p) {
        Map<String, Object> m = new LinkedHashMap<>(summarize(p));
        m.put("inputSchemaJson", p.getInputSchemaJson());
        m.put("brainstormPromptYaml", truncate(p.getBrainstormPromptYaml()));
        m.put("scriptPromptYaml", truncate(p.getScriptPromptYaml()));
        m.put("scriptCriticPromptYaml", truncate(p.getScriptCriticPromptYaml()));
        m.put("storyboardPromptYaml", truncate(p.getStoryboardPromptYaml()));
        m.put("assistantDirectorPromptYaml", truncate(p.getAssistantDirectorPromptYaml()));
        m.put("bgmMoodPromptYaml", truncate(p.getBgmMoodPromptYaml()));
        m.put("imageConfigJson", p.getImageConfigJson());
        m.put("voiceConfigJson", p.getVoiceConfigJson());
        m.put("hookPageFlipSoundUrl", p.getHookPageFlipSoundUrl());
        return m;
    }

    private static Object truncate(String s) {
        if (s == null) return null;
        if (s.length() <= MAX_YAML_RETURN) return s;
        return Map.of(
                "_truncated", true,
                "originalLength", s.length(),
                "preview", s.substring(0, MAX_YAML_RETURN)
        );
    }

    // -------- list_presets --------
    private class ListPresets implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "visibility", Map.of(
                                    "type", "string",
                                    "enum", List.of("public", "private", "all"),
                                    "description", "可选过滤;不传或 all 返回全部"
                            )
                    ),
                    "required", List.of()
            );
            return ChatRequest.Tool.of(
                    "list_presets",
                    "列出所有预设(摘要;不含 prompt yaml 等长字段)。需要详情时再用 get_preset/get_preset_by_name。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String filter = args != null && args.hasNonNull("visibility") ? args.get("visibility").asText() : "all";
            // adminMode=true 取全部,然后按 visibility 过滤
            List<Preset> all = presetService.listVisible(true, null);
            List<Map<String, Object>> out = all.stream()
                    .filter(p -> "all".equals(filter) || filter.equals(p.getVisibility()))
                    .map(PresetReadTools.this::summarize)
                    .toList();
            return Map.of("count", out.size(), "presets", out);
        }
    }

    // -------- get_preset --------
    private class GetPreset implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "id", Map.of("type", "integer", "description", "preset.id")
                    ),
                    "required", List.of("id")
            );
            return ChatRequest.Tool.of(
                    "get_preset",
                    "按 id 读完整预设(prompt yaml 过长会截断到前 4000 字)。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("id").asLong();
            return detail(presetService.get(id));
        }
    }

    private class GetPresetByName implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "name", Map.of("type", "string", "description", "preset.name 内部 key,如 lifecopy")
                    ),
                    "required", List.of("name")
            );
            return ChatRequest.Tool.of(
                    "get_preset_by_name",
                    "按内部 name(如 lifecopy/freeform)读完整预设。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String name = args.get("name").asText();
            return detail(presetService.getByName(name));
        }
    }

    private class ListPresetVersions implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "presetId", Map.of("type", "integer")
                    ),
                    "required", List.of("presetId")
            );
            return ChatRequest.Tool.of(
                    "list_preset_versions",
                    "列出某预设的全部历史快照(回滚目标候选)。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = args.get("presetId").asLong();
            List<PresetVersion> versions = presetService.listVersions(id);
            List<Map<String, Object>> rows = versions.stream().map(v -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("version", v.getVersion());
                m.put("comment", v.getComment());
                m.put("createdAt", v.getCreatedAt() == null ? null : v.getCreatedAt().toString());
                return m;
            }).toList();
            return Map.of("presetId", id, "count", rows.size(), "versions", rows);
        }
    }
}
