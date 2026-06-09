package com.auteur.agent;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 增强版 ToolHandler:写入业务内容前给前端展示一份 diff(before/after)。
 *
 * AgentLoopService 检测到 handler 实现了本接口,会:
 *   1) 在审批前先调 preview(args) 拿一份 Preview;
 *   2) 把 Preview 字段塞进 tool_approval_request 的 SSE 事件,前端 ApprovalCard 渲染 DiffView。
 *   3) 用户批准 → 调 execute(args) 真落库;拒绝 → 跳过。
 *
 * 注:preview 应该是只读的(不动 DB)。execute 才是真写。
 */
public interface PreviewableHandler extends ToolHandler {

    /** 写工具默认 risk = WRITE,子类可改为 ACTION。 */
    @Override
    default Risk risk() { return Risk.WRITE; }

    /**
     * 计算 before/after。读 DB 看当前值,跟 args 里的新值比。
     * 抛异常 = 直接落库 ERROR,不进审批环节(让 LLM 自纠正)。
     */
    Preview preview(JsonNode args);

    /**
     * before/after 是字符串(对结构化对象,序列化成 yaml/json 文本再 diff)。
     * fieldName 形如 "script_section.textContent" 或 "shot.promptZh",前端用作 diff 标题。
     * summary 是一句话总结(可空),用来在 LLM 回复时简要描述。
     */
    record Preview(String fieldName, String before, String after, String summary) {}
}
