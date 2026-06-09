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
 * 文件位于 classpath:agent/system_prompt.md;读取一次缓存到内存。
 * 后续可在文件头加 "version: vN" 行用于回放老会话兼容性,目前先返回硬编码 "v1"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SystemPromptBuilder {

    private final ResourceLoader resourceLoader;

    @Value("classpath:agent/system_prompt.md")
    private Resource promptResource;

    private volatile String cached;

    public synchronized String build() {
        if (cached != null) return cached;
        try {
            byte[] bytes = promptResource.getInputStream().readAllBytes();
            cached = new String(bytes, StandardCharsets.UTF_8);
            log.info("[Agent] system prompt 已加载,长度={}", cached.length());
        } catch (IOException e) {
            log.warn("[Agent] 加载 system prompt 失败,使用兜底文本: {}", e.toString());
            cached = "你是 Auteur 的运营助手。请使用注册的工具协助用户管理预设、提示词和系统配置。";
        }
        return cached;
    }

    public String version() {
        return "v1";
    }
}
