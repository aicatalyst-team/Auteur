package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.auteur.llm.LlmToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 把 Agent 消息持久化抽成独立 Component,绕开 Spring 自调用不触发 @Transactional 的坑。
 *
 * 每个 public 方法都是一个独立短事务,不持有 LLM 长调用的连接。
 *
 * 设计:DB 存原文,不做存储期截断 — 之前的 32K 截断会(a)悄悄丢用户输入(b)让 SSE 直播版本和刷新后看到的版本不一致。
 *   token 控制下放到 AgentLoopService.replayMessages → truncateForReplay,只在喂给 LLM 那一刻按字符上限截。
 *   tool_calls_json / tool_args_json 仍只 warn 不动 — 截断会破坏 OpenAI 协议(LLM 重放时反序列化 ToolCall 失败)。
 *   超大值看到 warn 后由调用方自己决定是否拒绝该工具调用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMessagePersister {

    /** content / tool_args_json 等大字段超过这个阈值打 warn 提示有大调用。仅日志,不截断。 */
    private static final int WARN_HUGE_CHARS = 64_000;

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentMessage saveUserMessage(AgentSession session, String userText) {
        int seq = messageRepo.countBySessionId(session.getId()) + 1;
        AgentMessage m = new AgentMessage();
        m.setSessionId(session.getId());
        m.setSeq(seq);
        m.setRole("user");
        m.setContent(warnIfHuge(userText, "user.content"));
        AgentMessage saved = messageRepo.save(m);

        if (seq == 1 && (session.getTitle() == null || session.getTitle().isBlank())) {
            String title = userText == null ? "新会话" : userText.strip();
            // title 是展示用短字段,这里截断不影响 content
            if (title.length() > 40) {
                int keep = 40;
                if (Character.isHighSurrogate(title.charAt(keep - 1))) keep--;
                title = title.substring(0, keep);
            }
            session.setTitle(title);
        }
        sessionRepo.save(session);
        return saved;
    }

    @Transactional
    public AgentMessage saveAssistantWithTools(AgentSession session, LlmToolResult result) {
        int seq = messageRepo.countBySessionId(session.getId()) + 1;
        AgentMessage m = new AgentMessage();
        m.setSessionId(session.getId());
        m.setSeq(seq);
        m.setRole("assistant");
        m.setContent(warnIfHuge(result.getContent(), "assistant.content"));
        m.setToolCallsJson(warnIfHuge(toJson(result.getToolCalls()), "tool_calls_json"));
        m.setInputTokens(result.getInputTokens());
        m.setOutputTokens(result.getOutputTokens());
        m.setDurationMs(result.getDurationMs());
        return messageRepo.save(m);
    }

    @Transactional
    public AgentMessage saveAssistantText(AgentSession session, LlmToolResult result) {
        int seq = messageRepo.countBySessionId(session.getId()) + 1;
        AgentMessage m = new AgentMessage();
        m.setSessionId(session.getId());
        m.setSeq(seq);
        m.setRole("assistant");
        m.setContent(warnIfHuge(result.getContent(), "assistant.content"));
        m.setInputTokens(result.getInputTokens());
        m.setOutputTokens(result.getOutputTokens());
        m.setDurationMs(result.getDurationMs());
        return messageRepo.save(m);
    }

    @Transactional
    public AgentMessage saveToolResult(AgentSession session, String toolCallId, String toolName,
                                       String argsJson, String resultJson, String status) {
        int seq = messageRepo.countBySessionId(session.getId()) + 1;
        AgentMessage m = new AgentMessage();
        m.setSessionId(session.getId());
        m.setSeq(seq);
        m.setRole("tool");
        m.setToolCallId(toolCallId);
        m.setToolName(toolName);
        m.setToolArgsJson(warnIfHuge(argsJson, "tool_args_json:" + toolName));
        m.setContent(warnIfHuge(resultJson, "tool.content:" + toolName));
        m.setToolStatus(status);
        return messageRepo.save(m);
    }

    /** 只 warn 不截断:DB 持有原文,token 控制在 replay 时做。 */
    private String warnIfHuge(String s, String tag) {
        if (s != null && s.length() > WARN_HUGE_CHARS) {
            log.warn("[Agent] 字段超大({} 字符,DB 仍存原文,replay 时会按 char 上限截断给 LLM) tag={}",
                    s.length(), tag);
        }
        return s;
    }

    private String toJson(List<ChatRequest.ToolCall> toolCalls) {
        if (toolCalls == null) return null;
        try {
            return objectMapper.writeValueAsString(toolCalls);
        } catch (JsonProcessingException e) {
            log.warn("[Agent] tool_calls 序列化失败: {}", e.toString());
            return null;
        }
    }
}
