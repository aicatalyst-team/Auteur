package com.auteur.agent;

import com.auteur.llm.ChatRequest;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Agent 工具的两件套:
 *   - definition()  : OpenAI 兼容的 function/tool 元数据,启动时一次注册到 ToolRegistry,作为 LLM 的 tools 参数。
 *   - execute(args) : LLM 决定调时被派发,返回 JSON 字符串作为 tool message 的 content。
 *
 * 实现类应该是无状态的 Spring @Component,通过 @PostConstruct 自己向 ToolRegistry 注册。
 *
 * risk():声明工具的风险等级,AgentLoopService 据此决定是否走 HITL approval gate。
 *   - READ   : 只读工具,不审批,直接执行
 *   - WRITE  : 写入业务对象(预设/系统配置),审批后才执行
 *   - ACTION : 触发副作用动作(跑流水线/调外部 API/产生成本),必审批
 */
public interface ToolHandler {

    enum Risk { READ, WRITE, ACTION }

    /** 默认 READ。写工具/动作工具应 override 返回 WRITE/ACTION。 */
    default Risk risk() {
        return Risk.READ;
    }

    /** OpenAI tool 定义,作为 ChatRequest.tools 元素发给 LLM。 */
    ChatRequest.Tool definition();

    /**
     * 执行工具。args 是 LLM 给的 JSON 参数(已 parse);返回任意 JSON 节点,会被序列化成 tool message content。
     * 抛异常:被 AgentLoopService catch 后封装成 { error: "..." } 作为 tool 结果回灌给 LLM,LLM 自己决定纠正。
     */
    Object execute(JsonNode args);

    /** 工具名,等同 definition().getFunction().getName()。提供方便方法。 */
    default String name() {
        return definition().getFunction().getName();
    }
}
