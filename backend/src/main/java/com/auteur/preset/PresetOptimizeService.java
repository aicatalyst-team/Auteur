package com.auteur.preset;

import com.auteur.common.text.TextUtils;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * 预设"沟通优化"服务:用户在预设编辑器某一节(section)对当前配置不满意,
 * 提交自然语言反馈,由 LLM 重新生成该 section 涉及的字段。
 *
 * 不直接落库 — 仅返回新值,前端写回 draft,让用户决定是否保存为新版本。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PresetOptimizeService {

    private final LlmClient llmClient;
    private final PresetService presetService;
    private final ObjectMapper objectMapper;

    /** section → 该节涉及的 Preset 字段(camelCase)。前端 tab 与此一一对应。 */
    private static final Map<String, List<String>> SECTION_FIELDS = Map.ofEntries(
            Map.entry("basic", List.of("displayName", "description", "visibility", "ownerName")),
            Map.entry("input", List.of("inputSchemaJson")),
            Map.entry("brainstorm", List.of("brainstormPromptYaml")),
            Map.entry("script", List.of("scriptPromptYaml")),
            Map.entry("critic", List.of("scriptCriticPromptYaml", "scriptCriticThreshold")),
            Map.entry("storyboard", List.of("storyboardPromptYaml", "storyboardMode", "assistantDirectorPromptYaml")),
            Map.entry("image", List.of("imageConfigJson")),
            Map.entry("voice", List.of("voiceConfigJson")),
            Map.entry("bgm", List.of("bgmMoodPromptYaml", "bgmEnabled", "bgmLocked")),
            Map.entry("composition", List.of(
                    "compositionId", "formatWidth", "formatHeight", "watermarkText", "hookSegmentEnabled"))
    );

    private static final Map<String, String> SECTION_LABELS = Map.ofEntries(
            Map.entry("basic", "基本信息"),
            Map.entry("input", "输入字段"),
            Map.entry("brainstorm", "选题"),
            Map.entry("script", "编剧"),
            Map.entry("critic", "自审"),
            Map.entry("storyboard", "摄影"),
            Map.entry("image", "美术"),
            Map.entry("voice", "录音"),
            Map.entry("bgm", "BGM"),
            Map.entry("composition", "合成")
    );

    private static final Map<String, String> SECTION_GUIDANCE = Map.ofEntries(
            Map.entry("basic",
                    "这是预设的元信息(显示名/描述/可见性/所有者)。优化时关注文案的清晰度与准确性。"
                            + "visibility 取值只能是 'private' 或 'public',不要随意从 private 改为 public。"),
            Map.entry("input",
                    "inputSchemaJson 是 topic 创建表单的 JSON Schema(JSON 对象)。需符合 JSON Schema 规范,"
                            + "字段含 type/properties/required 等。前端 DynamicForm 据此渲染表单。"),
            Map.entry("brainstorm",
                    "brainstormPromptYaml 是选题 LLM Prompt 模板(YAML 字符串),必含 system 与 user 字段,"
                            + "可含 model / temperature。模板里 {{key}} 是变量占位符,只能保留或在合理时新增,不能随意删除。"
                            + "可用变量:n / archive_hint / done_topics / weight_table / top_features / bottom_features / prev_week_plan。"),
            Map.entry("script",
                    "scriptPromptYaml 是编剧 LLM Prompt 模板(YAML 字符串)。必含 system / user。模板里 {{key}} 是变量占位符,"
                            + "可引用 input_schema 字段(如 {{theme}})。优化时保持模板规范。"),
            Map.entry("critic",
                    "scriptCriticPromptYaml 是脚本自审 prompt(YAML,可空,空 = 不跑自审);"
                            + "scriptCriticThreshold 是阈值整数 0-100,典型 80,低于此分触发 REWRITE。"),
            Map.entry("storyboard",
                    "storyboardPromptYaml 是分镜 prompt(YAML,必填);"
                            + "storyboardMode 必须是 'PRECISE_BY_CUE' 或 'FREE',前者强制 anchor_cue_indices 严格连续覆盖;"
                            + "assistantDirectorPromptYaml 是助理导演 prompt(YAML,可空)。"),
            Map.entry("image",
                    "imageConfigJson 是图像生成配置(JSON 对象,可空)。常用字段:"
                            + "model / identity_lock_text / reference_image_path / style_suffix / style_tag / negative_prompt / image_size。"),
            Map.entry("voice",
                    "voiceConfigJson 是语音合成配置(JSON 对象,整体可空表示不要旁白)。"
                            + "字段:voice_id / speed_ratio / volume_ratio。"),
            Map.entry("bgm",
                    "bgmMoodPromptYaml 是 BGM mood 推荐 prompt(YAML,可空);"
                            + "bgmEnabled / bgmLocked 是布尔开关。仅 bgmEnabled=true 时 bgmLocked 才生效。"),
            Map.entry("composition",
                    "compositionId 是 Remotion composition 名(如 StoryHorizontal / StoryVertical / LifeCopy);"
                            + "formatWidth / formatHeight 是成片分辨率整数(横屏 1920×1080,竖屏 1080×1920);"
                            + "watermarkText 是左上角水印文本(空字符串 = 不加);hookSegmentEnabled 是布尔。")
    );

    public OptimizeResponse optimize(Long presetId, OptimizeRequest req) {
        if (req == null || req.section() == null || req.section().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "section 不能为空");
        }
        if (req.userFeedback() == null || req.userFeedback().isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "userFeedback 不能为空");
        }
        List<String> fields = SECTION_FIELDS.get(req.section());
        if (fields == null) {
            throw new ResponseStatusException(BAD_REQUEST, "未知 section: " + req.section());
        }
        Preset preset = presetService.get(presetId);

        Map<String, Object> currentValues = collectCurrentValues(preset, fields, req.currentValues());

        String currentJson = toPrettyJson(currentValues);
        String fieldList = String.join(", ", fields);
        String guidance = SECTION_GUIDANCE.getOrDefault(req.section(), "");
        String sectionLabel = SECTION_LABELS.getOrDefault(req.section(), req.section());

        String system = """
                你是 Auteur 视频创作工具的"预设配置优化助手"。
                用户正在编辑预设的某一节配置时不满意,你需要根据他们的自然语言反馈,
                重新生成这一节涉及的所有字段。

                【输出要求】
                1. 严格 JSON,不要 Markdown 代码块,不要前后任何解释文字。
                2. JSON 顶层只有两个 key:fields 与 explanation。
                3. fields 必须包含本节列出的全部字段(没改的字段也保留原值);字段名严格按用户消息中给出的列表,不要新增/删除/改名。
                4. 字段值的类型必须与"当前配置"一致(string / number / boolean / object)。
                   特别地,以 Yaml 结尾的字段值始终是 YAML 文本字符串;以 Json 结尾的字段值始终是 JSON 对象(不要套成字符串)。
                5. explanation 是一句中文,简述你做了什么改动,30-80 字。

                【该节语义说明】
                %s
                """.formatted(guidance);

        String user = """
                你正在优化预设 "%s"(displayName=%s)的【%s】配置。

                本节涉及的字段:%s

                ====== 当前配置(JSON)======
                %s

                ====== 用户反馈 ======
                %s

                请综合用户反馈调整配置。直接输出符合上述【输出要求】的 JSON。
                """.formatted(
                preset.getName(),
                TextUtils.safe(preset.getDisplayName()),
                sectionLabel,
                fieldList,
                currentJson,
                req.userFeedback().trim()
        );

        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("preset_optimize_" + req.section())
                .relatedType("PRESET")
                .relatedId(presetId)
                .temperature(0.4)
                .build();

        LlmResult result = llmClient.chat(spec, system, user);
        String raw = result.getContent();
        log.info("[PresetOptimize] presetId={} section={} feedbackChars={} outChars={} ms={}",
                presetId, req.section(),
                req.userFeedback().length(),
                raw == null ? 0 : raw.length(),
                result.getDurationMs());

        return parseLlmResponse(raw, req.section(), fields);
    }

    /** 取本节字段当前值:优先用前端传入的 currentValues(用户在编辑器里未保存的草稿),缺则回落 DB。 */
    private Map<String, Object> collectCurrentValues(
            Preset preset, List<String> fields, Map<String, Object> overrides
    ) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> dbView = presetToFieldMap(preset);
        for (String f : fields) {
            Object v;
            if (overrides != null && overrides.containsKey(f)) {
                v = overrides.get(f);
            } else {
                v = dbView.get(f);
            }
            result.put(f, normalizeJsonField(f, v));
        }
        return result;
    }

    /** 把 preset 实体浅展平成 Map(只包含我们关心的字段)。 */
    private Map<String, Object> presetToFieldMap(Preset preset) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("displayName", preset.getDisplayName());
        m.put("description", preset.getDescription());
        m.put("visibility", preset.getVisibility());
        m.put("ownerName", preset.getOwnerName());
        m.put("inputSchemaJson", parseJsonOrNull(preset.getInputSchemaJson()));
        m.put("brainstormPromptYaml", preset.getBrainstormPromptYaml());
        m.put("scriptPromptYaml", preset.getScriptPromptYaml());
        m.put("scriptCriticPromptYaml", preset.getScriptCriticPromptYaml());
        m.put("scriptCriticThreshold", preset.getScriptCriticThreshold());
        m.put("storyboardPromptYaml", preset.getStoryboardPromptYaml());
        m.put("storyboardMode", preset.getStoryboardMode());
        m.put("assistantDirectorPromptYaml", preset.getAssistantDirectorPromptYaml());
        m.put("bgmMoodPromptYaml", preset.getBgmMoodPromptYaml());
        m.put("imageConfigJson", parseJsonOrNull(preset.getImageConfigJson()));
        m.put("voiceConfigJson", parseJsonOrNull(preset.getVoiceConfigJson()));
        m.put("bgmEnabled", preset.isBgmEnabled());
        m.put("bgmLocked", preset.isBgmLocked());
        m.put("compositionId", preset.getCompositionId());
        m.put("formatWidth", preset.getFormatWidth());
        m.put("formatHeight", preset.getFormatHeight());
        m.put("watermarkText", preset.getWatermarkText());
        m.put("hookSegmentEnabled", preset.isHookSegmentEnabled());
        return m;
    }

    /** Json 字段如果传进来是 String,反序列化成对象;否则原样返回。 */
    private Object normalizeJsonField(String fieldName, Object value) {
        if (value == null) return null;
        if (!fieldName.endsWith("Json")) return value;
        if (value instanceof String s) return parseJsonOrNull(s);
        return value;
    }

    private Object parseJsonOrNull(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, Object.class);
        } catch (Exception e) {
            log.warn("[PresetOptimize] JSON 解析失败,作为原始字符串透传: {}", e.toString());
            return json;
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private OptimizeResponse parseLlmResponse(String raw, String section, List<String> fields) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "LLM 返回为空");
        }
        String stripped = TextUtils.stripCodeFence(raw).trim();
        // LLM 偶尔在 JSON 前后夹杂额外文本,截取最外层大括号
        int start = stripped.indexOf('{');
        int end = stripped.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "LLM 输出不是合法 JSON: " + TextUtils.preview(raw));
        }
        String json = stripped.substring(start, end + 1);
        Map<String, Object> parsed;
        try {
            parsed = objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "LLM 输出 JSON 解析失败: " + TextUtils.preview(raw));
        }

        Object fieldsObj = parsed.get("fields");
        if (!(fieldsObj instanceof Map<?, ?> rawFields)) {
            throw new ResponseStatusException(BAD_REQUEST, "LLM 输出缺少 fields 对象");
        }
        Map<String, Object> outFields = new LinkedHashMap<>();
        for (String f : fields) {
            if (rawFields.containsKey(f)) {
                outFields.put(f, rawFields.get(f));
            }
        }
        if (outFields.isEmpty()) {
            throw new ResponseStatusException(BAD_REQUEST, "LLM 输出 fields 不含本节任何字段");
        }
        String explanation = parsed.get("explanation") == null
                ? null : String.valueOf(parsed.get("explanation"));
        return new OptimizeResponse(section, outFields, explanation);
    }

    public record OptimizeRequest(
            String section,
            String userFeedback,
            Map<String, Object> currentValues
    ) {}

    public record OptimizeResponse(
            String section,
            Map<String, Object> fields,
            String explanation
    ) {}
}
