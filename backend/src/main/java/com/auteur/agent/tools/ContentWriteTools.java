package com.auteur.agent.tools;

import com.auteur.agent.PreviewableHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.agent.WriteToolHandler;
import com.auteur.cover.BrandIdentity;
import com.auteur.cover.BrandIdentityService;
import com.auteur.domain.FactCheckIssue;
import com.auteur.domain.FactCheckIssueRepository;
import com.auteur.domain.ScriptSection;
import com.auteur.domain.ScriptSectionRepository;
import com.auteur.domain.StoryboardShot;
import com.auteur.domain.StoryboardShotRepository;
import com.auteur.llm.ChatRequest;
import com.auteur.script.FactCheckFixService;
import com.auteur.script.ScriptService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * 圈三:业务内容写入工具(直接改脚本/分镜/品牌包)。
 *
 * 全部走 HITL 审批,且实现 PreviewableHandler 让用户在审批卡上看到 diff(before/after),
 * 而不是只看一个 args JSON — 这是圈三跟圈一/二的关键区别。
 *
 * apply_factcheck_fix 例外:内部要跑 LLM 决定替换串,无法干净分成 preview/execute 两步,
 * 用普通 WRITE risk(用户在审批卡上看到 issueId,自己去前端查 issue 详情)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ContentWriteTools {

    private final ToolRegistry registry;
    private final ScriptService scriptService;
    private final ScriptSectionRepository sectionRepo;
    private final StoryboardShotRepository shotRepo;
    private final BrandIdentityService brandService;
    private final FactCheckIssueRepository issueRepo;
    private final FactCheckFixService fixService;

    @PostConstruct
    public void init() {
        registry.register(new UpdateScriptSection());
        registry.register(new UpdateShotPrompt());
        registry.register(new UpdateBrandIdentity());
        registry.register(new ApplyFactcheckFix());
    }

    // ============ update_script_section ============
    private class UpdateScriptSection implements PreviewableHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "update_script_section",
                    "改某 script 的某段(section)文本和/或标题。会同步重建 script.full_text。" +
                            "审批卡会显示 textContent 的 before/after diff。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "scriptId", Map.of("type", "integer"),
                                    "sectionId", Map.of("type", "integer"),
                                    "newTextContent", Map.of("type", "string", "description", "新文本(整段替换)"),
                                    "newTitle", Map.of("type", "string", "description", "新标题(可空保留原值)")
                            ),
                            "required", List.of("scriptId", "sectionId", "newTextContent")
                    )
            );
        }

        @Override
        public Preview preview(JsonNode args) {
            ScriptSection s = loadSection(args);
            String newText = requireString(args, "newTextContent");
            String fieldName = "script_section[" + s.getSectionCode() + "].textContent";
            return new Preview(fieldName, s.getTextContent() == null ? "" : s.getTextContent(), newText,
                    "改 section " + s.getSectionCode() + " 的文本(" +
                            (s.getTextContent() == null ? 0 : s.getTextContent().length()) + " → " +
                            newText.length() + " 字)");
        }

        @Override
        public Object execute(JsonNode args) {
            long scriptId = requireLong(args, "scriptId");
            long sectionId = requireLong(args, "sectionId");
            String newText = requireString(args, "newTextContent");
            String newTitle = args.hasNonNull("newTitle") ? args.get("newTitle").asText() : null;
            ScriptSection cur = loadSection(args);
            String oldTitle = cur.getTitle();
            ScriptSection saved = scriptService.updateSection(scriptId, sectionId, newText,
                    newTitle != null ? newTitle : oldTitle);
            log.info("[Agent] update_script_section sid={} → ok", sectionId);
            return Map.of(
                    "ok", true,
                    "sectionId", saved.getId(),
                    "sectionCode", saved.getSectionCode(),
                    "newLength", saved.getTextContent() == null ? 0 : saved.getTextContent().length(),
                    "fullTextRebuilt", true
            );
        }

        private ScriptSection loadSection(JsonNode args) {
            long sid = requireLong(args, "sectionId");
            ScriptSection s = sectionRepo.findById(sid)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "section id=" + sid + " 不存在"));
            long scriptId = requireLong(args, "scriptId");
            // 兼容老脏数据(script_id=NULL)— 给出明确提示而不是按"不属于此 script"误导 LLM 反复换 scriptId 重试
            if (s.getScriptId() == null) {
                throw new IllegalStateException("section " + sid + " 的 script_id 为 NULL(脏数据),无法安全写入,请先在 UI 修复");
            }
            if (!Long.valueOf(scriptId).equals(s.getScriptId())) {
                throw new IllegalArgumentException(
                        "section " + sid + " 实际属于 script " + s.getScriptId() + ",请求的 scriptId=" + scriptId + " 不匹配");
            }
            return s;
        }
    }

    // ============ update_shot_prompt ============
    private class UpdateShotPrompt implements PreviewableHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "update_shot_prompt",
                    "改某分镜的 prompt 字段(zh/en/negative,任一可空保留原值)。" +
                            "适合纠正 LLM 自动出 prompt 时的脱敏失败、构图偏差等。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "shotId", Map.of("type", "integer"),
                                    "promptZh", Map.of("type", "string"),
                                    "promptEn", Map.of("type", "string"),
                                    "negativePrompt", Map.of("type", "string")
                            ),
                            "required", List.of("shotId")
                    )
            );
        }

        @Override
        public Preview preview(JsonNode args) {
            StoryboardShot shot = loadShot(args);
            StringBuilder before = new StringBuilder();
            StringBuilder after = new StringBuilder();
            int changedFields = 0;
            if (args.hasNonNull("promptZh")) {
                before.append("[promptZh]\n").append(s(shot.getPromptZh())).append("\n\n");
                after.append("[promptZh]\n").append(args.get("promptZh").asText()).append("\n\n");
                changedFields++;
            }
            if (args.hasNonNull("promptEn")) {
                before.append("[promptEn]\n").append(s(shot.getPromptEn())).append("\n\n");
                after.append("[promptEn]\n").append(args.get("promptEn").asText()).append("\n\n");
                changedFields++;
            }
            if (args.hasNonNull("negativePrompt")) {
                before.append("[negativePrompt]\n").append(s(shot.getNegativePrompt())).append("\n\n");
                after.append("[negativePrompt]\n").append(args.get("negativePrompt").asText()).append("\n\n");
                changedFields++;
            }
            if (changedFields == 0) {
                throw new IllegalArgumentException("至少要传 promptZh / promptEn / negativePrompt 之一");
            }
            return new Preview(
                    "shot[#" + shot.getShotIndex() + "].prompt(" + changedFields + " 字段)",
                    before.toString().trim(),
                    after.toString().trim(),
                    "改第 " + shot.getShotIndex() + " 镜的 " + changedFields + " 个 prompt 字段"
            );
        }

        @Override
        public Object execute(JsonNode args) {
            StoryboardShot shot = loadShot(args);
            if (args.hasNonNull("promptZh")) shot.setPromptZh(args.get("promptZh").asText());
            if (args.hasNonNull("promptEn")) shot.setPromptEn(args.get("promptEn").asText());
            if (args.hasNonNull("negativePrompt")) shot.setNegativePrompt(args.get("negativePrompt").asText());
            shotRepo.save(shot);
            log.info("[Agent] update_shot_prompt shotId={} → ok", shot.getId());
            return Map.of(
                    "ok", true,
                    "shotId", shot.getId(),
                    "shotIndex", shot.getShotIndex()
            );
        }

        private StoryboardShot loadShot(JsonNode args) {
            long sid = requireLong(args, "shotId");
            return shotRepo.findById(sid)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "shot id=" + sid + " 不存在"));
        }
    }

    // ============ update_brand_identity ============
    private class UpdateBrandIdentity implements PreviewableHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "update_brand_identity",
                    "修改账号品牌包(单行表,id=1)。任一字段缺省=不动,显式传则覆盖。" +
                            "改色或字体后所有视频封面的右下角标会同步变化。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of(
                                    "brandName", Map.of("type", "string"),
                                    "authorName", Map.of("type", "string"),
                                    "primaryColor", Map.of("type", "string"),
                                    "secondaryColor", Map.of("type", "string"),
                                    "accentColor", Map.of("type", "string"),
                                    "bgColor", Map.of("type", "string"),
                                    "titleFont", Map.of("type", "string"),
                                    "defaultTemplateId", Map.of("type", "string")
                            ),
                            "required", List.of()
                    )
            );
        }

        @Override
        public Preview preview(JsonNode args) {
            BrandIdentity cur = brandService.getOrCreate();
            Map<String, String> changes = collectChanges(cur, args);
            if (changes.isEmpty()) {
                throw new IllegalArgumentException("没有任何要改的字段");
            }
            StringBuilder before = new StringBuilder();
            StringBuilder after = new StringBuilder();
            for (Map.Entry<String, String> e : changes.entrySet()) {
                String key = e.getKey();
                String oldV = currentValueOf(cur, key);
                before.append(key).append(" = ").append(s(oldV)).append("\n");
                after.append(key).append(" = ").append(e.getValue()).append("\n");
            }
            return new Preview("brand_identity(" + changes.size() + " 字段)",
                    before.toString().trim(), after.toString().trim(),
                    "改品牌包 " + changes.keySet());
        }

        @Override
        public Object execute(JsonNode args) {
            BrandIdentity cur = brandService.getOrCreate();
            Map<String, String> changes = collectChanges(cur, args);
            applyChanges(cur, changes);
            BrandIdentity saved = brandService.save(cur);
            log.info("[Agent] update_brand_identity changes={}", changes.keySet());
            return Map.of("ok", true, "fieldsChanged", changes.keySet());
        }

        private Map<String, String> collectChanges(BrandIdentity cur, JsonNode args) {
            Map<String, String> out = new LinkedHashMap<>();
            for (String k : List.of("brandName", "authorName", "primaryColor", "secondaryColor",
                    "accentColor", "bgColor", "titleFont", "defaultTemplateId")) {
                if (args.hasNonNull(k)) out.put(k, args.get(k).asText());
            }
            return out;
        }

        private String currentValueOf(BrandIdentity b, String key) {
            return switch (key) {
                case "brandName" -> b.getBrandName();
                case "authorName" -> b.getAuthorName();
                case "primaryColor" -> b.getPrimaryColor();
                case "secondaryColor" -> b.getSecondaryColor();
                case "accentColor" -> b.getAccentColor();
                case "bgColor" -> b.getBgColor();
                case "titleFont" -> b.getTitleFont();
                case "defaultTemplateId" -> b.getDefaultTemplateId();
                default -> null;
            };
        }

        private void applyChanges(BrandIdentity b, Map<String, String> changes) {
            changes.forEach((k, v) -> {
                switch (k) {
                    case "brandName" -> b.setBrandName(v);
                    case "authorName" -> b.setAuthorName(v);
                    case "primaryColor" -> b.setPrimaryColor(v);
                    case "secondaryColor" -> b.setSecondaryColor(v);
                    case "accentColor" -> b.setAccentColor(v);
                    case "bgColor" -> b.setBgColor(v);
                    case "titleFont" -> b.setTitleFont(v);
                    case "defaultTemplateId" -> b.setDefaultTemplateId(v);
                }
            });
        }
    }

    // ============ apply_factcheck_fix ============
    private class ApplyFactcheckFix implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            return ChatRequest.Tool.of(
                    "apply_factcheck_fix",
                    "应用某个事实核查 issue 的修复建议。内部跑 LLM 生成精确替换串,落到 section 并标 resolved。" +
                            "注:不像别的写工具,本工具不带 diff preview(因为 diff 需要先调 LLM,跟 execute 重复)。" +
                            "建议调用前先用 list_factcheck_issues / get_factcheck_issue 让用户看清楚 issue 详情。",
                    Map.of(
                            "type", "object",
                            "properties", Map.of("issueId", Map.of("type", "integer")),
                            "required", List.of("issueId")
                    )
            );
        }

        @Override
        public Object execute(JsonNode args) {
            long id = requireLong(args, "issueId");
            FactCheckIssue issue = issueRepo.findById(id)
                    .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "issue id=" + id + " 不存在"));
            if (Boolean.TRUE.equals(issue.getResolved())) {
                return Map.of("applied", false, "reason", "issue 已经被处理过了");
            }
            FactCheckFixService.ApplyResult r = fixService.applyFix(id);
            log.info("[Agent] apply_factcheck_fix issueId={} action={}", id, r.action());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("applied", r.applied());
            out.put("action", r.action());
            out.put("rationale", r.rationale());
            out.put("sectionId", r.sectionId());
            out.put("sectionCode", r.sectionCode());
            return out;
        }
    }

    private static String s(String v) {
        return v == null ? "" : v;
    }

    /**
     * 必填字符串字段守卫:LLM 漏字段时抛"缺少必填字段 X"而不是 args.get(...).asText() NPE。
     * 被 AgentLoopService 的 try/catch 捕获后变成有意义的 ERROR 提示,LLM 能据此自纠正。
     */
    private static String requireString(JsonNode args, String name) {
        JsonNode n = args == null ? null : args.get(name);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("缺少必填字段: " + name);
        }
        return n.asText();
    }

    private static long requireLong(JsonNode args, String name) {
        JsonNode n = args == null ? null : args.get(name);
        if (n == null || n.isNull()) {
            throw new IllegalArgumentException("缺少必填字段: " + name);
        }
        if (!n.canConvertToLong()) {
            throw new IllegalArgumentException("字段 " + name + " 不是有效整数: " + n);
        }
        return n.asLong();
    }
}
