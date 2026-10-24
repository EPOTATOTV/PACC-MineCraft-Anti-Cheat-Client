-- =====================================================================
-- V44: R-02 告警队列 Redis 化
-- 队列本身已由 V35 建表。Redis 负责即时投递，数据库仍是持久化账本；
-- 失败重试与死信需要记录次数和最后一次错误，便于管理端排查。
--
-- 兼容性：MySQL 8.0 / H2（MODE=MySQL）均支持 ADD COLUMN。
-- =====================================================================

ALTER TABLE t_alert_notify_queue
    ADD COLUMN attempts INT NOT NULL DEFAULT 0 COMMENT '已失败重试次数';

ALTER TABLE t_alert_notify_queue
    ADD COLUMN last_error VARCHAR(255) NULL COMMENT '最近一次投递失败原因';
