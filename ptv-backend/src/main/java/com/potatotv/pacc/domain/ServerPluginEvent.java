package com.potatotv.pacc.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * 服务端检测事件：由服务端插件经 /api/v1/server/event 上报。
 * <p>pteid 可空——玩家未安装 PACC 玩家端时无法关联账号，事件仍如实留痕。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_server_plugin_event")
public class ServerPluginEvent {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "server_id", nullable = false, length = 64)
    private String serverId;

    @Column(name = "player_uuid", length = 64)
    private String playerUuid;

    @Column(name = "player_name", length = 64)
    private String playerName;

    @Column(length = 64)
    private String pteid;

    /** MOVE / COMBAT / PACKET / INVENTORY / INTERACTION / CLIENT_BRAND / ADDON / DATA_CONSISTENCY。 */
    @Column(name = "check_type", nullable = false, length = 32)
    private String checkType;

    @Column(name = "cheat_type", length = 64)
    private String cheatType;

    @Builder.Default
    @Column(nullable = false)
    private int confidence = 0;

    @Column(length = 4000)
    private String detail;

    @Builder.Default
    @Column(name = "event_ts_ms", nullable = false)
    private long eventTsMs = 0;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** 本次是否联动触发了 PTV 红屏（仅高置信事件）。 */
    @Builder.Default
    @Column(name = "redscreen_triggered", nullable = false)
    private boolean redscreenTriggered = false;

    @Column(name = "alert_id", length = 64)
    private String alertId;
}
