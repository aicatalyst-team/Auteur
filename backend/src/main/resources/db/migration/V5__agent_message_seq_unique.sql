-- V5: agent_message.(session_id, seq) 升级为 UNIQUE
--
-- 背景:V4 时只建了普通索引,但 AgentMessagePersister 用 count(*)+1 算 seq,
--   并发场景下两个 turn 同时跑同一会话会拿到相同 seq。
--   普通索引兜不住,改 UNIQUE 后冲突会在 DB 层失败,不至于产生坏数据。
--
-- 已经存在的重复行(理论上没有,本期前没有并发触发路径):本迁移会因唯一冲突失败,
--   届时手动清理后再跑。

ALTER TABLE `agent_message`
  DROP INDEX `idx_agent_msg_session_seq`,
  ADD UNIQUE KEY `uk_agent_msg_session_seq` (`session_id`, `seq`);
