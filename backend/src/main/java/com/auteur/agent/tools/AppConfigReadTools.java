package com.auteur.agent.tools;

import com.auteur.agent.ToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.llm.ChatRequest;
import com.auteur.runtimeconfig.RuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 系统配置只读工具(app_config)。
 *
 * - list_app_configs:    全部配置项(secret 已 mask 成 abcd****wxyz)
 * - get_app_config:      单 key 取一项
 *
 * 写入工具放第 3 步 AppConfigWriteTools。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppConfigReadTools {

    private final ToolRegistry registry;
    private final RuntimeConfig runtimeConfig;

    @PostConstruct
    public void init() {
        registry.register(new ListAppConfigs());
        registry.register(new GetAppConfig());
    }

    private Map<String, Object> view(RuntimeConfig.ConfigView v) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("configKey", v.configKey());
        m.put("description", v.description());
        m.put("secret", v.secret());
        m.put("category", v.category());
        m.put("hasDbValue", v.hasDbValue());
        m.put("displayValue", v.displayValue());
        m.put("updatedAt", v.updatedAt());
        return m;
    }

    private class ListAppConfigs implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "category", Map.of(
                                    "type", "string",
                                    "description", "可选过滤,如 llm/tos/voice/bgm。不传返回全部"
                            )
                    ),
                    "required", List.of()
            );
            return ChatRequest.Tool.of(
                    "list_app_configs",
                    "列出所有运行时配置项(LLM/TOS/语音/BGM 等密钥与 URL)。secret 字段已被 mask。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String category = args != null && args.hasNonNull("category") ? args.get("category").asText() : null;
            List<Map<String, Object>> rows = runtimeConfig.listAll().stream()
                    .filter(v -> category == null || category.isBlank() || category.equals(v.category()))
                    .map(AppConfigReadTools.this::view)
                    .toList();
            return Map.of("count", rows.size(), "configs", rows);
        }
    }

    private class GetAppConfig implements ToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "configKey", Map.of(
                                    "type", "string",
                                    "description", "形如 auteur.llm.api-key"
                            )
                    ),
                    "required", List.of("configKey")
            );
            return ChatRequest.Tool.of(
                    "get_app_config",
                    "读单个运行时配置项(secret 已 mask)。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String key = args.get("configKey").asText();
            return runtimeConfig.listAll().stream()
                    .filter(v -> v.configKey().equals(key))
                    .findFirst()
                    .map(AppConfigReadTools.this::view)
                    .orElseThrow(() -> new IllegalArgumentException("未找到配置项: " + key));
        }
    }
}
