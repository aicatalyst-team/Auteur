package com.auteur.agent;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Agent system prompt 加载器。
 *
 * 拼接两部分:
 *   1. classpath:agent/system_prompt.md — 通用规则、角色、工具速查
 *   2. SkillRegistry.buildCatalog() — 任务剧本目录(每个 skill 一行 name+summary,按需 read_skill 读全文)
 *
 * 第二部分让 system prompt 不会被 4 个 skill 全文塞满几千 token,只放目录。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final ResourceLoader resourceLoader;
    private final SkillRegistry skillRegistry;

    @Value("classpath:agent/system_prompt.md")
    private Resource promptResource;

    private volatile String cached;

    public synchronized String build() {
        if (cached != null) return cached;
        String basePrompt;
        try {
            byte[] bytes = promptResource.getInputStream().readAllBytes();
            basePrompt = new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("[Agent] 加载 system prompt 失败,使用兜底文本: {}", e.toString());
            basePrompt = "你是 Auteur 的运营助手。请使用注册的工具协助用户管理预设、提示词和系统配置。";
        }
        String catalog = skillRegistry.buildCatalog();
        cached = catalog.isEmpty() ? basePrompt : (basePrompt + "\n\n" + catalog);
        log.info("[Agent] system prompt 已加载,长度={} (含 skill 目录 {} 字)",
                cached.length(), catalog.length());
        return cached;
    }

    public String version() {
        return "v1";
    }
}
