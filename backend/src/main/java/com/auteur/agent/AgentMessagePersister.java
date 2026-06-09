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
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentMessagePersister {

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
        m.setContent(userText);
        AgentMessage saved = messageRepo.save(m);

        if (seq == 1 && (session.getTitle() == null || session.getTitle().isBlank())) {
            String title = userText == null ? "新会话" : userText.strip();
            if (title.length() > 40) title = title.substring(0, 40);
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
        m.setContent(result.getContent());
        m.setToolCallsJson(toJson(result.getToolCalls()));
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
        m.setContent(result.getContent());
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
        m.setToolArgsJson(argsJson);
        m.setContent(resultJson);
        m.setToolStatus(status);
        return messageRepo.save(m);
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
