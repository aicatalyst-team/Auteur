package com.auteur.agent.tools;

import com.auteur.agent.WriteToolHandler;
import com.auteur.agent.ToolRegistry;
import com.auteur.llm.ChatRequest;
import com.auteur.runtimeconfig.RuntimeConfig;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 系统配置写入工具。
 *
 *   - set_app_config:    单 key 写入(空字符串=清空走 yml 兜底)
 *
 * 注:secret 字段(api-key 之类)前端列表时已经 mask;LLM 不会看到原值,
 *    所以"改密钥"的能力依赖用户在对话里显式提供新密钥。LLM 不应该用 mask 占位回写。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppConfigWriteTools {

    private final ToolRegistry registry;
    private final RuntimeConfig runtimeConfig;

    @PostConstruct
    public void init() {
        registry.register(new SetAppConfig());
    }

    private class SetAppConfig implements WriteToolHandler {
        @Override
        public ChatRequest.Tool definition() {
            Map<String, Object> schema = Map.of(
                    "type", "object",
                    "properties", Map.of(
                            "configKey", Map.of(
                                    "type", "string",
                                    "description", "配置项 key,如 auteur.llm.default-model"
                            ),
                            "value", Map.of(
                                    "type", "string",
                                    "description", "新值;空字符串表示清空(回落到 yml 默认)。" +
                                            "secret 字段切勿回写 mask 占位(abcd****wxyz),要求用户提供完整原值。"
                            )
                    ),
                    "required", List.of("configKey", "value")
            );
            return ChatRequest.Tool.of(
                    "set_app_config",
                    "写入单个运行时配置项。改 secret 类(密钥)前必须由用户在对话中显式提供完整新值。",
                    schema
            );
        }

        @Override
        public Object execute(JsonNode args) {
            String key = args.get("configKey").asText();
            String value = args.hasNonNull("value") ? args.get("value").asText() : "";

            // 阻止 LLM 把 mask 占位写回:形如 abcd****wxyz / 含 ****
            if (value.contains("****")) {
                throw new IllegalArgumentException(
                        "拒绝写入:value 含 mask 占位(****)。secret 类配置必须由用户提供完整原值。");
            }

            runtimeConfig.set(key, value);
            log.info("[Agent] set_app_config key={} cleared={}", key, value.isEmpty());
            return Map.of(
                    "ok", true,
                    "configKey", key,
                    "cleared", value.isEmpty()
            );
        }
    }
}
