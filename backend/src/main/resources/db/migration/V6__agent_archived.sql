-- V5: Agent 控制台 — 为将来的归档/清理留口子。当前不实施清理逻辑,只加字段和索引。
--
-- agent_session.archived:
--   30 天以上的老会话可以由后续运维脚本(或 UI 一键归档)标 archived=1,
--   listSessions 端点就能加一个 ?includeArchived=false 参数过滤掉默认视图。
--   暂时所有会话默认 archived=0。
--
-- agent_message.created_at 索引:
--   归档逻辑或按时间排序的清理脚本(WHERE created_at < ?)会用到。

ALTER TABLE `agent_session`
  ADD COLUMN `archived` TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '归档标记;1=不在默认会话列表里显示。当前未启用清理逻辑,仅留口子',
  ADD KEY `idx_agent_session_archived` (`archived`, `updated_at`);

ALTER TABLE `agent_message`
  ADD KEY `idx_agent_message_created` (`created_at`);
