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
 * 服务端处置留痕：插件在本服实际执行的告警 / 踢出 / 封禁记录。
 * <p>PTV 只做记录与统计，不据此执行跨服封禁，也不对接第三方封禁系统。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_server_plugin_punishment")
public class ServerPluginPunishment {

    @Id
    @Column(length = 64)
    private String id;

    @Column(name = "server_id", nullable = false, length = 64)
    private String serverId;

    @Column(name = "player_uuid", length = 64)
    private String playerUuid;

    /** ALERT / KICK / TEMP_BAN / BAN。 */
    @Column(name = "punishment_type", nullable = false, length = 32)
    private String punishmentType;

    @Column(length = 512)
    private String reason;

    @Builder.Default
    @Column(name = "duration_ms", nullable = false)
    private long durationMs = 0;

    @Column(length = 64)
    private String operator;

    @Builder.Default
    @Column(name = "occurred_ts_ms", nullable = false)
    private long occurredTsMs = 0;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
}
