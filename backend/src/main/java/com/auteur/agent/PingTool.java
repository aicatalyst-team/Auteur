package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 健康检查工具:第 1 步骨架阶段就注册一个,验证 LLM ↔ tool_call ↔ 派发 ↔ 结果回灌 全链路打通。
 *
 * LLM 看到用户说"ping" / "测试一下"时,应该调本工具,本工具回 { ok:true, message:"pong",echo:<args.message?> },
 * LLM 据此输出最终回复。
 */
@Component
@RequiredArgsConstructor
public class PingTool implements ToolHandler {

    private final ToolRegistry registry;

    @PostConstruct
    public void init() {
        registry.register(this);
    }

    @Override
    public ChatRequest.Tool definition() {
        Map<String, Object> schema = Map.of(
                "type", "object",
                "properties", Map.of(
                        "message", Map.of(
                                "type", "string",
                                "description", "可选的回声内容,工具会原样回到结果里"
                        )
                ),
                "required", java.util.List.of()
        );
        return ChatRequest.Tool.of(
                "ping",
                "Agent 健康检查工具。用户问'是否在线/能否调用工具/ping'时调用,返回 pong。",
                schema
        );
    }

    @Override
    public Object execute(JsonNode args) {
        String echo = args != null && args.hasNonNull("message") ? args.get("message").asText() : null;
        return Map.of(
                "ok", true,
                "message", "pong",
                "echo", echo == null ? "" : echo
        );
    }
}
