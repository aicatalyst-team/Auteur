package com.auteur.agent;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * SSE 推给前端的事件。type 决定 data 的形态:
 *   - "user_saved"     data = { messageId, seq }
 *   - "assistant_chunk" data = { text }                       (本期未流式,占位)
 *   - "tool_call"      data = { id, name, argsJson }          LLM 决定调工具
 *   - "tool_result"    data = { id, name, status, resultJson } 工具执行完毕
 *   - "assistant_done" data = { messageId, seq, content }     LLM 输出最终回复
 *   - "done"           data = { sessionId }                    本轮全部结束
 *   - "error"          data = { message }
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
