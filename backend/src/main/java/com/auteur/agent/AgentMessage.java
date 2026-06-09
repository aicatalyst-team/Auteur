package com.auteur.agent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Agent 对话内的一条消息。一行能表达 4 种角色:
 *   - role=user      : content = 用户输入
 *   - role=assistant : content = LLM 文本(可空); tool_calls_json = LLM 决定调的工具数组(可空)
 *   - role=tool      : tool_call_id + tool_name + content(=工具结果) + tool_args_json + tool_status
 *   - role=system    : content = 注入的额外指令(目前未用,留口子)
 *
 * 重放协议:按 seq 排序读出来,按 role 转回 ChatRequest.Message:
 *   - assistant 行 → Message.assistant(content, parsed tool_calls)
 *   - tool      行 → Message.tool(tool_call_id, tool_name, content)
 */
@Entity
@Table(name = "agent_message")
@Getter
@Setter
public class AgentMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id", nullable = false)
    private Long sessionId;

    @Column(nullable = false)
    private Integer seq;

    @Column(nullable = false, length = 16)
    private String role;

    @Column(columnDefinition = "MEDIUMTEXT")
    private String content;

    @Column(name = "tool_calls_json", columnDefinition = "MEDIUMTEXT")
    private String toolCallsJson;

    @Column(name = "tool_call_id", length = 100)
    private String toolCallId;

    @Column(name = "tool_name", length = 100)
    private String toolName;

    @Column(name = "tool_args_json", columnDefinition = "MEDIUMTEXT")
    private String toolArgsJson;

    @Column(name = "tool_status", length = 16)
    private String toolStatus;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "duration_ms")
    private Integer durationMs;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
