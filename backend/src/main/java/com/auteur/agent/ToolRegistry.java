package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册表:启动期由各个 *Tools 类的 @PostConstruct 把自身 register 进来。
 * AgentLoopService 用它拼 ChatRequest.tools 列表,以及在 LLM 决定调时按名查 handler。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, ToolHandler> byName = new ConcurrentHashMap<>();

    public synchronized void register(ToolHandler handler) {
        String n = handler.name();
        if (byName.containsKey(n)) {
            log.warn("[Agent] 工具 {} 已被注册,后注册的会覆盖", n);
        }
        byName.put(n, handler);
        log.info("[Agent] 工具注册: {}", n);
    }

    public ToolHandler find(String name) {
        return byName.get(name);
    }

    /** 给 LLM 的 tools 数组(每次发请求都拷一份)。 */
    public List<ChatRequest.Tool> definitions() {
        List<ChatRequest.Tool> out = new ArrayList<>(byName.size());
        for (ToolHandler h : byName.values()) {
            out.add(h.definition());
        }
        return out;
    }

    public Map<String, ToolHandler> snapshot() {
        return Collections.unmodifiableMap(byName);
    }
}
