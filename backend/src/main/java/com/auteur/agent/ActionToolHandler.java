package com.auteur.agent;

/**
 * 标记型 ToolHandler:默认 risk = ACTION,触发副作用动作(跑流水线/调外部 API/产生成本)。
 *
 * 用途:9 个 PipelineTrigger 工具高度同形,如果每个手写一行 `@Override public Risk risk() { return Risk.ACTION; }`,
 *   忘了写就静默下沉到 ToolHandler 默认的 READ,绕过 HITL 审批闸门 —— 这正是写入审批要防的事。
 *   改成"实现这个接口即默认 ACTION",忘写也安全。
 */
public interface ActionToolHandler extends ToolHandler {
    @Override
    default Risk risk() { return Risk.ACTION; }
}
