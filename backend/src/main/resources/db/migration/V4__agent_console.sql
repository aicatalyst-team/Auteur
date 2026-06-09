-- V4: Agent 控制台 — 对话式入口的会话/消息表
--
-- 设计:
--   agent_session 一行 = 一个对话会话 (UI 左栏列表)。
--   agent_message 一行 = 会话里的一条消息(user / assistant / tool / system),
--     content_json 存完整 JSON,把 LLM 多轮里的 tool_calls / tool_call_id / 文本统一塞下,
--     这样持久化和重放一行不丢。
--
-- 不存 LLM 调用的 token / cost — 现有 cost_log 由 LlmClient 写入,Agent 只在 op 字段加 agent.* 区分即可。

CREATE TABLE `agent_session` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `title` varchar(200) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '会话标题,首条用户消息截取或 LLM 自动起名',
  `model` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT '本会话固定使用的模型;NULL 走 RuntimeConfig 默认',
  `system_prompt_version` varchar(40) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'system prompt 模板版本,变更后老会话仍可重放',
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_session_updated` (`updated_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 对话会话';

CREATE TABLE `agent_message` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `session_id` bigint NOT NULL,
  `seq` int NOT NULL COMMENT '会话内顺序号,从 1 起',
  `role` varchar(16) COLLATE utf8mb4_unicode_ci NOT NULL COMMENT 'user / assistant / tool / system',
  `content` mediumtext COLLATE utf8mb4_unicode_ci COMMENT '纯文本内容(assistant 仅工具调用时可空)',
  `tool_calls_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'role=assistant 且 LLM 触发工具时的 tool_calls 数组 JSON',
  `tool_call_id` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'role=tool 时的 tool_call.id',
  `tool_name` varchar(100) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'role=tool 时的工具名',
  `tool_args_json` mediumtext COLLATE utf8mb4_unicode_ci COMMENT 'role=tool 时该工具被调用时传入的参数 JSON,便于 UI 重放',
  `tool_status` varchar(16) COLLATE utf8mb4_unicode_ci DEFAULT NULL COMMENT 'role=tool 时:OK / ERROR',
  `input_tokens` int DEFAULT NULL,
  `output_tokens` int DEFAULT NULL,
  `duration_ms` int DEFAULT NULL,
  `created_at` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_agent_msg_session_seq` (`session_id`,`seq`),
  CONSTRAINT `fk_agent_message_session` FOREIGN KEY (`session_id`) REFERENCES `agent_session` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='Agent 对话单条消息(含 tool_call / tool_result 全量回放数据)';
