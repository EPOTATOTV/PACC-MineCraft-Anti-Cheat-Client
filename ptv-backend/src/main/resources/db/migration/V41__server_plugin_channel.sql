-- =====================================================================
-- V41: 服务端插件通道（设计文档 §4 双模式架构 / §6 服务端插件模式）
-- 覆盖：t_server_plugin_registration（注册）、t_server_plugin_event（检测事件）、
--       t_server_plugin_punishment（处置记录）三张表。
--
-- 契约背景：服务端插件（Paper/Spigot/Nukkit/Nukkit-MOT/AllayMC/Geyser 扩展）在运维自己的
--   服务器进程内运行，经 /api/v1/server/{register,event,punishment} 用共享密钥签名上报。
--   检测结果与证据只经 PACC 独立通道记录，PTV 不反向读取任何游戏服务器数据库。
--
-- 安全边界：处罚记录仅用于留痕与统计；PTV 不做跨服封禁，也不对接任何第三方封禁系统。
--   踢出 / 封禁是否生效由插件按运维本服配置决定，默认仅告警。
--
-- 兼容性：生产 MySQL 8.0（utf8mb4 / InnoDB）；本地 H2（MODE=MySQL）同样执行。
--   三张表均为新建，不触碰既有数据。已合入的迁移脚本不可改，后续变更追加新版本号。
-- =====================================================================

-- 1. t_server_plugin_registration：一台服务器一条，插件启动时 register 覆盖更新。
CREATE TABLE IF NOT EXISTS t_server_plugin_registration (
    server_id      VARCHAR(64)  NOT NULL COMMENT '服务器唯一 ID（运维配置，插件上报）',
    server_type    VARCHAR(32)  NOT NULL COMMENT 'PAPER/SPIGOT/NUKKIT/NUKKIT_MOT/ALLAYMC/GEYSER',
    plugin_version VARCHAR(32)  NULL     COMMENT 'PACC 插件版本',
    server_version VARCHAR(64)  NULL     COMMENT '服务端软件版本（如 1.21.1 / 1.20.80）',
    server_name    VARCHAR(128) NULL     COMMENT '服务器展示名',
    max_players    INT          NOT NULL DEFAULT 0 COMMENT '最大在线人数',
    registered_at  DATETIME(6)  NOT NULL COMMENT '首次注册时间',
    last_seen_at   DATETIME(6)  NOT NULL COMMENT '最近一次上报时间',
    PRIMARY KEY (server_id),
    KEY idx_sp_registration_type (server_type)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 2. t_server_plugin_event：检测事件明细。pteid 可空（玩家未装玩家端 / 无法关联）。
--    redscreen_triggered 标记本次是否联动触发了 PTV 红屏（高分事件才有）。
CREATE TABLE IF NOT EXISTS t_server_plugin_event (
    id                 VARCHAR(64)  NOT NULL COMMENT '事件 ID',
    server_id          VARCHAR(64)  NOT NULL COMMENT '来源服务器',
    player_uuid        VARCHAR(64)  NULL     COMMENT '服务端可见的玩家 UUID',
    player_name        VARCHAR(64)  NULL     COMMENT '玩家名',
    pteid              VARCHAR(64)  NULL     COMMENT '关联的玩家端 PTEID（未安装时为空）',
    check_type         VARCHAR(32)  NOT NULL COMMENT 'MOVE/COMBAT/PACKET/INVENTORY/INTERACTION/CLIENT_BRAND/ADDON/DATA_CONSISTENCY',
    cheat_type         VARCHAR(64)  NULL     COMMENT '疑似作弊类型',
    confidence         INT          NOT NULL DEFAULT 0 COMMENT '置信度 0-100',
    detail             VARCHAR(4000) NULL    COMMENT '检测详情（已脱敏，不含原始数据包）',
    event_ts_ms        BIGINT       NOT NULL DEFAULT 0 COMMENT '插件侧事件时间戳（毫秒）',
    received_at        DATETIME(6)  NOT NULL COMMENT 'PTV 接收时间',
    redscreen_triggered TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '是否联动触发红屏',
    alert_id           VARCHAR(64)  NULL     COMMENT '联动红屏告警 ID',
    PRIMARY KEY (id),
    KEY idx_sp_event_server (server_id, received_at),
    KEY idx_sp_event_pteid (pteid, received_at),
    KEY idx_sp_event_cheat (cheat_type, received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- 3. t_server_plugin_punishment：插件实际执行的处置留痕。operator 记录是自动还是管理员手动。
CREATE TABLE IF NOT EXISTS t_server_plugin_punishment (
    id             VARCHAR(64)  NOT NULL COMMENT '记录 ID',
    server_id      VARCHAR(64)  NOT NULL COMMENT '来源服务器',
    player_uuid    VARCHAR(64)  NULL     COMMENT '玩家 UUID',
    punishment_type VARCHAR(32) NOT NULL COMMENT 'ALERT/KICK/TEMP_BAN/BAN',
    reason         VARCHAR(512) NULL     COMMENT '处置原因',
    duration_ms    BIGINT       NOT NULL DEFAULT 0 COMMENT '临时封禁时长（毫秒）；0 表示不限',
    operator       VARCHAR(64)  NULL     COMMENT 'ACC_AUTO（自动）/ 管理员名',
    occurred_ts_ms BIGINT       NOT NULL DEFAULT 0 COMMENT '处置发生时间戳（毫秒）',
    received_at    DATETIME(6)  NOT NULL COMMENT 'PTV 接收时间',
    PRIMARY KEY (id),
    KEY idx_sp_punishment_server (server_id, received_at),
    KEY idx_sp_punishment_player (player_uuid, received_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
