-- V7: 删掉 V6 顺手加的 idx_agent_message_created 索引。
-- 加它的初衷是"将来按时间清理消息时用",但目前没有任何查询走 created_at 排序
-- (主用法是 messageRepo.findBySessionIdOrderBySeqAsc,走 idx_agent_msg_session_seq),
-- 索引每写一条消息要维护一次 BTree,白付成本。等真正有按时间清理的脚本时再加回来。
--
-- 注:V6 已 applied 不可再改,所以拆出 V7 来 DROP — 这是 Flyway 的正道。

ALTER TABLE `agent_message`
  DROP KEY `idx_agent_message_created`;
