-- =====================================================================
-- V45: R-05 跨端证据链归档
-- 实时拼装的证据链会受原始事件留存周期影响，归档保存当时的完整快照与校验和。
-- 分布式锁由应用层 Redis SET NX EX 实现，表结构不感知锁。
--
-- 兼容性：MySQL 8.0 / H2（MODE=MySQL）均支持 LONGTEXT 与复合索引。
-- =====================================================================

CREATE TABLE IF NOT EXISTS t_cross_evidence_archive (
    id         VARCHAR(64)  NOT NULL COMMENT '归档主键',
    pteid      VARCHAR(64)  NOT NULL COMMENT '关联 PTEID',
    operator   VARCHAR(128) NULL     COMMENT '触发归档的管理员',
    snapshot   LONGTEXT     NOT NULL COMMENT '证据链 JSON 快照',
    sha256     VARCHAR(64)  NOT NULL COMMENT '快照 SHA-256',
    created_at DATETIME(6)  NOT NULL COMMENT '归档时间',
    PRIMARY KEY (id),
    KEY idx_ce_archive_pteid (pteid, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
