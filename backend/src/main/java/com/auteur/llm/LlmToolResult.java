package com.auteur.llm;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * chatWithTools 的返回:文本 + 可能的 tool_calls。
 * finishReason="tool_calls" 表示 LLM 想调工具,调用方应执行后追加 tool message 再发回 LLM;
 * finishReason="stop" 表示 LLM 输出最终回复,本轮结束。
 */
@Data
@Builder
public class LlmToolResult {
    private String content;
    private List<ChatRequest.ToolCall> toolCalls;
    private String finishReason;
    private String model;
    private String provider;
    private Integer inputTokens;
    private Integer outputTokens;
    private Integer durationMs;
}
