package com.auteur.preset;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.TreeNode;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;

/**
 * @JsonRawValue 只管序列化(输出时不加引号),反序列化方向 Jackson 仍按 String 处理 —
 * 前端 PUT 进来的 JSON 字段如果是对象/数组会触发 MismatchedInputException。
 *
 * 本反序列化器:
 *   - 文本 → 透传(`{"a":1}` 字符串保持原样)
 *   - 对象/数组 → toString 序列化成紧凑 JSON 文本
 *   - null → null
 *
 * 配 @JsonDeserialize(using = JsonRawStringDeserializer.class) 用在 String 字段上。
 */
public class JsonRawStringDeserializer extends JsonDeserializer<String> {

    @Override
    public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonToken t = p.currentToken();
        if (t == JsonToken.VALUE_NULL) return null;
        if (t == JsonToken.VALUE_STRING) return p.getText();
        if (t == JsonToken.VALUE_NUMBER_INT || t == JsonToken.VALUE_NUMBER_FLOAT
                || t == JsonToken.VALUE_TRUE || t == JsonToken.VALUE_FALSE) {
            return p.getText();
        }
        // 对象 / 数组 → 读完整子树后用 Jackson 序列化成紧凑文本
        TreeNode tree = p.readValueAsTree();
        return tree.toString();
    }
}
