package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.auteur.llm.LlmCallSpec;
import com.auteur.llm.LlmClient;
import com.auteur.llm.LlmToolResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Agent Loop 核心。
 *
 * turn(sessionId, userText, emitter):
 *   1) 落 user 消息 → 推 user_saved
 *   2) 重放 (system + 历史) 给 LLM,带上注册的工具
 *   3) LLM 返回:
 *        - 含 tool_calls → 落 assistant 行(带 tool_calls_json)→ 推 assistant_done
 *                       → 逐个执行工具(每个落一行 tool 消息 + 推 tool_call/tool_result)
 *                       → 回到 step 2(让 LLM 看到结果)
 *        - 纯文本     → 落 assistant 终消息 → 推 assistant_done + done,完
 *   4) 兜底:循环超过 MAX_TURNS(8)推 error 终止
 *
 * 持久化全部委托给 AgentMessagePersister,本类只编排流程,不直接持库。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentLoopService {

    private static final int MAX_TURNS = 8;

    private final AgentSessionRepository sessionRepo;
    private final AgentMessageRepository messageRepo;
    private final AgentMessagePersister persister;
    private final ToolRegistry toolRegistry;
    private final LlmClient llmClient;
    private final SystemPromptBuilder systemPromptBuilder;
    private final ObjectMapper objectMapper;

    public void turn(Long sessionId, String userText, SseEmitter emitter) {
        Consumer<AgentEvent> sink = ev -> sendEvent(emitter, ev);
        try {
            AgentSession session = sessionRepo.findById(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("session 不存在: " + sessionId));

            AgentMessage userMsg = persister.saveUserMessage(session, userText);
            sink.accept(AgentEvent.of("user_saved", Map.of(
                    "messageId", userMsg.getId(),
                    "seq", userMsg.getSeq(),
                    "content", userText == null ? "" : userText
            )));

            for (int turn = 0; turn < MAX_TURNS; turn++) {
                List<ChatRequest.Message> history = replayMessages(session);
                LlmToolResult result = callLlm(session, history);

                if (hasToolCalls(result)) {
                    AgentMessage assistantRow = persister.saveAssistantWithTools(session, result);
                    sink.accept(AgentEvent.of("assistant_done", Map.of(
                            "messageId", assistantRow.getId(),
                            "seq", assistantRow.getSeq(),
                            "content", result.getContent() == null ? "" : result.getContent(),
                            "hasToolCalls", true
                    )));

                    for (ChatRequest.ToolCall call : result.getToolCalls()) {
                        executeAndPersistTool(session, call, sink);
                    }
                    continue;
                }

                AgentMessage assistantRow = persister.saveAssistantText(session, result);
                sink.accept(AgentEvent.of("assistant_done", Map.of(
                        "messageId", assistantRow.getId(),
                        "seq", assistantRow.getSeq(),
                        "content", result.getContent() == null ? "" : result.getContent(),
                        "hasToolCalls", false
                )));
                sink.accept(AgentEvent.of("done", Map.of("sessionId", sessionId)));
                emitter.complete();
                return;
            }

            // 跑满 MAX_TURNS 仍在调工具:再来一次禁用工具的 LLM 调用,让它给出收尾文本,
            // 否则历史会停在 assistant→tool 截断状态,下一轮重放时模型会迷惑。
            log.warn("[Agent] sessionId={} 达到 MAX_TURNS={},强制禁工具收尾", sessionId, MAX_TURNS);
            List<ChatRequest.Message> history = replayMessages(session);
            history.add(ChatRequest.Message.system(
                    "已达到本轮工具调用上限(" + MAX_TURNS + " 次)。请基于已有 tool 结果直接给用户写一段总结/下一步建议,不要再调用任何工具。"));
            LlmToolResult finalResult = llmClient.chatWithTools(
                    LlmCallSpec.builder()
                            .operation("agent.chat.cap")
                            .relatedType("AGENT_SESSION")
                            .relatedId(session.getId())
                            .model(session.getModel())
                            .temperature(0.3)
                            .build(),
                    history,
                    List.of() // 禁用工具
            );
            AgentMessage capRow = persister.saveAssistantText(session, finalResult);
            sink.accept(AgentEvent.of("assistant_done", Map.of(
                    "messageId", capRow.getId(),
                    "seq", capRow.getSeq(),
                    "content", finalResult.getContent() == null ? "" : finalResult.getContent(),
                    "hasToolCalls", false
            )));
            sink.accept(AgentEvent.of("error",
                    Map.of("message", "已达到本轮工具调用上限 " + MAX_TURNS + " 次,Agent 强制收尾。如需继续请重新发起。")));
            emitter.complete();
        } catch (Exception e) {
            log.error("[Agent] turn 失败 sessionId={}: {}", sessionId, e.toString(), e);
            sink.accept(AgentEvent.of("error",
                    Map.of("message", e.getMessage() == null ? e.toString() : e.getMessage())));
            emitter.completeWithError(e);
        }
    }

    private LlmToolResult callLlm(AgentSession session, List<ChatRequest.Message> history) {
        LlmCallSpec spec = LlmCallSpec.builder()
                .operation("agent.chat")
                .relatedType("AGENT_SESSION")
                .relatedId(session.getId())
                .model(session.getModel())
                .temperature(0.3)
                .build();
        return llmClient.chatWithTools(spec, history, toolRegistry.definitions());
    }

    private boolean hasToolCalls(LlmToolResult r) {
        return r.getToolCalls() != null && !r.getToolCalls().isEmpty();
    }

    private List<ChatRequest.Message> replayMessages(AgentSession session) {
        List<AgentMessage> rows = messageRepo.findBySessionIdOrderBySeqAsc(session.getId());
        List<ChatRequest.Message> out = new ArrayList<>(rows.size() + 1);
        out.add(ChatRequest.Message.system(systemPromptBuilder.build()));

        for (AgentMessage row : rows) {
            switch (row.getRole()) {
                case "user" -> out.add(ChatRequest.Message.user(row.getContent() == null ? "" : row.getContent()));
                case "assistant" -> {
                    List<ChatRequest.ToolCall> tcs = parseToolCalls(row.getToolCallsJson());
                    out.add(ChatRequest.Message.assistant(row.getContent(), tcs));
                }
                case "tool" -> out.add(ChatRequest.Message.tool(
                        row.getToolCallId(),
                        row.getToolName(),
                        row.getContent() == null ? "" : row.getContent()
                ));
                default -> log.warn("[Agent] 跳过未知 role={} msgId={}", row.getRole(), row.getId());
            }
        }
        return out;
    }

    private List<ChatRequest.ToolCall> parseToolCalls(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(
                    json,
                    objectMapper.getTypeFactory().constructCollectionType(List.class, ChatRequest.ToolCall.class));
        } catch (JsonProcessingException e) {
            // 历史 assistant 行的 tool_calls 解析失败:静默降级会让重放出现 assistant→tool 错位,
            // 模型行为不可预期。直接抛错让本轮失败,用户可以删掉这条会话或人工修数据。
            throw new IllegalStateException(
                    "tool_calls_json 反序列化失败,无法重放历史:" + e.getMessage(), e);
        }
    }

    private void executeAndPersistTool(AgentSession session, ChatRequest.ToolCall call, Consumer<AgentEvent> sink) {
        String name = call.getFunction() == null ? null : call.getFunction().getName();
        String argsJson = call.getFunction() == null ? null : call.getFunction().getArguments();

        sink.accept(AgentEvent.of("tool_call", Map.of(
                "id", call.getId() == null ? "" : call.getId(),
                "name", name == null ? "" : name,
                "argsJson", argsJson == null ? "" : argsJson
        )));

        ToolHandler handler = toolRegistry.find(name);
        String resultJson;
        String status;
        if (handler == null) {
            resultJson = toJson(Map.of("error", "未注册的工具: " + name));
            status = "ERROR";
        } else {
            try {
                JsonNode args = parseArgs(argsJson);
                Object out = handler.execute(args);
                resultJson = toJson(out);
                status = "OK";
            } catch (Exception e) {
                log.warn("[Agent] tool {} 执行失败: {}", name, e.toString());
                resultJson = toJson(Map.of(
                        "error", e.getMessage() == null ? e.toString() : e.getMessage()
                ));
                status = "ERROR";
            }
        }

        AgentMessage row = persister.saveToolResult(session, call.getId(), name, argsJson, resultJson, status);
        sink.accept(AgentEvent.of("tool_result", Map.of(
                "messageId", row.getId(),
                "seq", row.getSeq(),
                "id", call.getId() == null ? "" : call.getId(),
                "name", name == null ? "" : name,
                "status", status,
                "resultJson", resultJson
        )));
    }

    private JsonNode parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) return objectMapper.createObjectNode();
        try {
            return objectMapper.readTree(argsJson);
        } catch (JsonProcessingException e) {
            log.warn("[Agent] tool 参数 JSON 解析失败,原文: {}", argsJson);
            return objectMapper.createObjectNode();
        }
    }

    private String toJson(Object o) {
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"json_serialize_failed\"}";
        }
    }

    private void sendEvent(SseEmitter emitter, AgentEvent ev) {
        try {
            emitter.send(SseEmitter.event().name(ev.getType()).data(ev.getData()));
        } catch (IOException e) {
            log.warn("[Agent] SSE 推送失败 type={}: {}", ev.getType(), e.toString());
        }
    }
}
