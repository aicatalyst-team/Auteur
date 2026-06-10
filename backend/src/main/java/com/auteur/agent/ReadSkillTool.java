package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * read_skill(name) — 按名读 skill 全文。READ 工具不审批。
 *
 * 配合 SkillRegistry:system prompt 末尾只列每个 skill 的 (name, summary, when),
 * LLM 决定调写工具前用本工具读对应剧本拿全文。这样:
 *   - 节省 token(不在 system prompt 里塞所有剧本全文)
 *   - 让"是否参考剧本"成为 LLM 显式决策(可观测)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadSkillTool implements ToolHandler {

    private final ToolRegistry registry;
    private final SkillRegistry skillRegistry;

    @PostConstruct
    public void init() {
        registry.register(this);
    }

    @Override
    public ChatRequest.Tool definition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string",
                                "description", "skill name,从 system prompt 末尾的'任务剧本目录'里挑一个")
                ),
                "required", List.of("name")
        );
        return ChatRequest.Tool.of(
                "read_skill",
                "按 name 读取一个'任务剧本'的全文。剧本里写明了某类任务的必填字段、操作流程、" +
                        "常见误区。**调用关键写/动作工具前应该先读对应剧本**(如改预设前 read_skill('preset-modification'))。" +
                        "目录见 system prompt 末尾。",
                schema
        );
    }

    @Override
    public Object execute(JsonNode args) {
        String name = args.get("name").asText();
        SkillRegistry.Skill s = skillRegistry.find(name);
        if (s == null) {
            return Map.of(
                    "found", false,
                    "name", name,
                    "available", skillRegistry.getSkills().keySet(),
                    "hint", "未找到该 skill。可选的见 'available' 字段,或重看 system prompt 末尾的剧本目录。"
            );
        }
        log.info("[Agent] read_skill name={}", name);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("found", true);
        out.put("name", s.name());
        out.put("summary", s.summary());
        out.put("when", s.when());
        out.put("body", s.body());
        return out;
    }
}
