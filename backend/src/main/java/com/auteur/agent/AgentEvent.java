package com.auteur.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SSE 推给前端的事件。type 决定 data 的形态:
 *   - "user_saved"            data = { messageId, seq, content }
 *   - "assistant_chunk"       data = { text }                       (本期未流式,占位)
 *   - "tool_call"             data = { id, name, argsJson }          LLM 决定调工具
 *   - "tool_approval_request" data = { id, name, argsJson, risk, timeoutSeconds, diff? }
 *                                                                  WRITE/ACTION 工具执行前的审批请求,
 *                                                                  PreviewableHandler 会附加 diff: { fieldName, before, after, summary }
 *                                                                  前端应渲染审批卡(有 diff 时用 DiffView)+ 调 POST /agent/sessions/{id}/approve
 *   - "tool_result"           data = { id, name, status, resultJson, messageId, seq }
 *                                                                  status: OK / ERROR / REJECTED
 *   - "assistant_done"        data = { messageId, seq, content, hasToolCalls }
 *   - "done"                  data = { sessionId }                    本轮全部结束
 *   - "error"                 data = { message }
 */
@Data
@AllArgsConstructor
public class AgentEvent {
    private String type;
    private Object data;

    public static AgentEvent of(String type, Object data) {
        return new AgentEvent(type, data);
    }
}
