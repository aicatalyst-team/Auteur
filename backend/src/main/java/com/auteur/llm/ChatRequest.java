package com.auteur.llm;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatRequest {

    private String model;
    private List<Message> messages;
    private Double temperature;
    /** 上限输出 token；为 null 时不下发该字段，由网关用默认值。snake_case 跟 OpenAI 兼容接口对齐。 */
    private Integer max_tokens;

    /** Function/Tool 定义；为 null 时不下发，跟原有调用完全兼容。 */
    private List<Tool> tools;
    /** "auto" / "none" / { type:"function", function:{name:"..."} }。null 走默认。 */
    private Object tool_choice;

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Message {
        private String role;
        /** 文字模式：String；多模态：List<Map<String,Object>>；assistant 仅工具调用时可为 null。 */
        private Object content;
        /** assistant 决定调用工具时回填。 */
        private List<ToolCall> tool_calls;
        /** role=tool 时必填,关联到上一条 assistant 的 tool_call.id。 */
        private String tool_call_id;
        /** role=tool 时填工具名（部分网关需要）。 */
        private String name;

        public static Message system(String content) {
            Message m = new Message();
            m.role = "system";
            m.content = content;
            return m;
        }

        public static Message user(String content) {
            Message m = new Message();
            m.role = "user";
            m.content = content;
            return m;
        }

        public static Message assistant(String content, List<ToolCall> toolCalls) {
            Message m = new Message();
            m.role = "assistant";
            m.content = content;
            m.tool_calls = (toolCalls == null || toolCalls.isEmpty()) ? null : toolCalls;
            return m;
        }

        public static Message tool(String toolCallId, String toolName, String content) {
            Message m = new Message();
            m.role = "tool";
            m.tool_call_id = toolCallId;
            m.name = toolName;
            m.content = content;
            return m;
        }

        /** 多模态 user 消息：一段文字 + 一张图片 URL（OpenAI 兼容形态）。 */
        public static Message userWithImage(String text, String imageUrl) {
            Message m = new Message();
            m.role = "user";
            m.content = List.of(
                    Map.of("type", "text", "text", text),
                    Map.of("type", "image_url", "image_url", Map.of("url", imageUrl))
            );
            return m;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Tool {
        private String type = "function";
        private Function function;

        public static Tool of(String name, String description, Object parametersSchema) {
            Tool t = new Tool();
            Function f = new Function();
            f.setName(name);
            f.setDescription(description);
            f.setParameters(parametersSchema);
            t.setFunction(f);
            return t;
        }
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class Function {
        private String name;
        private String description;
        /** JSON Schema(任意 Map / POJO)。 */
        private Object parameters;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ToolCall {
        private String id;
        private String type = "function";
        private FunctionCall function;
    }

    @Data
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class FunctionCall {
        private String name;
        /** OpenAI 协议:这里是字符串(JSON-encoded)。 */
        private String arguments;
    }
}
