package com.auteur.agent;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Agent skill 注册表。启动期扫描 classpath:agent/skills/*.md,解析 YAML frontmatter,
 * 把每个 skill 的 metadata(name/summary/when) + 全文加载到内存。
 *
 * skill 是按需加载的"任务剧本":system prompt 末尾只列每个 skill 的 (name, summary, when),
 * LLM 决定调写工具前用 read_skill(name) 读全文。这样 token 不会被剧本一次性吃满,
 * 同时让 LLM 显式决定该不该读。
 *
 * 文件格式:YAML frontmatter + markdown 正文,见 skills/topic-creation.md。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillRegistry {

    private static final Pattern FRONTMATTER = Pattern.compile(
            "^---\\s*\\n(.*?)\\n---\\s*\\n(.*)$", Pattern.DOTALL);
    /** 简易 YAML 解析:只支持 key: value 单行,够 frontmatter 用。 */
    private static final Pattern KEY_VALUE = Pattern.compile("^([a-zA-Z][\\w-]*)\\s*:\\s*(.*)$");

    /** 按 name 索引,TreeMap 让目录按字典序稳定。 */
    @Getter
    private final Map<String, Skill> skills = new TreeMap<>();

    @PostConstruct
    public void init() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources("classpath:agent/skills/*.md");
            for (Resource r : resources) {
                try {
                    Skill s = load(r);
                    if (s != null) {
                        skills.put(s.name(), s);
                        log.info("[Agent] skill 加载: name={} ({} 字)", s.name(), s.body().length());
                    }
                } catch (Exception e) {
                    log.warn("[Agent] 加载 skill {} 失败: {}", r.getFilename(), e.toString());
                }
            }
            log.info("[Agent] 共加载 {} 个 skill", skills.size());
        } catch (IOException e) {
            log.warn("[Agent] 扫描 skills 目录失败: {}", e.toString());
        }
    }

    private Skill load(Resource r) throws IOException {
        String content = new String(r.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.matches()) {
            log.warn("[Agent] skill 文件 {} 没有 YAML frontmatter,跳过", r.getFilename());
            return null;
        }
        Map<String, String> meta = parseFrontmatter(m.group(1));
        String body = m.group(2).trim();
        String name = meta.getOrDefault("name", stripExt(r.getFilename()));
        String summary = meta.getOrDefault("summary", "");
        String when = meta.getOrDefault("when", "");
        return new Skill(name, summary, when, body);
    }

    private Map<String, String> parseFrontmatter(String text) {
        Map<String, String> out = new TreeMap<>();
        for (String line : text.split("\\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            Matcher m = KEY_VALUE.matcher(line);
            if (m.matches()) {
                out.put(m.group(1), m.group(2).trim());
            }
        }
        return out;
    }

    private String stripExt(String filename) {
        if (filename == null) return "unknown";
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    public Skill find(String name) {
        return skills.get(name);
    }

    public List<Skill> all() {
        return Collections.unmodifiableList(new java.util.ArrayList<>(skills.values()));
    }

    /**
     * 拼一段插到 system prompt 末尾的 skill 目录。每个 skill 一行 (name + summary + when)。
     * LLM 看到目录就知道存在这些剧本,需要时调 read_skill(name) 读全文。
     */
    public String buildCatalog() {
        if (skills.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("## 任务剧本目录(按需通过 `read_skill(name)` 读全文)\n\n");
        sb.append("调用关键写/动作工具前,你应该先 `read_skill` 看对应剧本(里面有必填字段、流程、误区)。规则如下:\n\n");
        for (Skill s : skills.values()) {
            sb.append("- **`").append(s.name()).append("`** — ").append(s.summary()).append("\n");
            if (!s.when().isBlank()) {
                sb.append("  - 何时读:").append(s.when()).append("\n");
            }
        }
        return sb.toString();
    }

    public record Skill(String name, String summary, String when, String body) {}
}
