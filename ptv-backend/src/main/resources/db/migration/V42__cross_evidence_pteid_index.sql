-- =====================================================================
-- V42: P-06 跨端证据链
-- 玩家端事件与服务端插件事件按 PTEID 合并查询。服务端事件表已有
-- idx_sp_event_pteid；玩家端事件表在 V1 建表时漏了 pteid 索引，
-- 现有 /api/player/protection/events 只能全表扫描，这里补上。
--
-- 兼容性：生产 MySQL 8.0 / 本地 H2（MODE=MySQL）均支持 CREATE INDEX。
-- =====================================================================

CREATE INDEX idx_detection_event_pteid ON t_detection_event (pteid, occurred_at);
