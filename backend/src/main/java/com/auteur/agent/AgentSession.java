package com.auteur.agent;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Agent 对话会话(一个 /chat 左栏条目)。
 *
 * 字段刻意瘦:title 用首条 user message 截取,model 允许会话级覆盖 RuntimeConfig 默认。
 * system_prompt_version 让 prompt 模板演进后老会话仍能重放。
 */
@Entity
@Table(name = "agent_session")
@Getter
@Setter
public class AgentSession {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 200)
    private String title;

    @Column(length = 100)
    private String model;

    @Column(name = "system_prompt_version", length = 40)
    private String systemPromptVersion;

    /**
     * 归档标记。1=不在默认会话列表显示。
     * 当前没有清理逻辑,仅留字段和索引;后续可加 UI 一键归档 + 后台清理脚本。
     */
    @Column(nullable = false)
    private boolean archived = false;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
