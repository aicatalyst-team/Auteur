package com.auteur.agent;

/**
 * 标记型 ToolHandler:默认 risk = WRITE,写业务对象但不带 diff preview。
 *
 * 跟 PreviewableHandler 区别:本接口只声明 risk,不要求实现 preview/before/after。
 *   适合写"小颗粒度"字段(如配置 key=value),用户在审批卡看 args JSON 就够。
 *   要给用户看 before/after diff 的(如改 script section / shot prompt),用 PreviewableHandler。
 */
public interface WriteToolHandler extends ToolHandler {
    @Override
    default Risk risk() { return Risk.WRITE; }
}
