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
 * 服务端插件注册记录：一台服务器一条，插件启动时上报、按 serverId 覆盖更新。
 * <p>服务端插件运行在运维自己的服务器进程内，仅上报本服务器维度信息；PTV 不反向读取游戏服务器数据。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "t_server_plugin_registration")
public class ServerPluginRegistration {

    @Id
    @Column(name = "server_id", length = 64)
    private String serverId;

    /** PAPER / SPIGOT / NUKKIT / NUKKIT_MOT / ALLAYMC / GEYSER。 */
    @Column(name = "server_type", nullable = false, length = 32)
    private String serverType;

    @Column(name = "plugin_version", length = 32)
    private String pluginVersion;

    @Column(name = "server_version", length = 64)
    private String serverVersion;

    @Column(name = "server_name", length = 128)
    private String serverName;

    @Builder.Default
    @Column(name = "max_players", nullable = false)
    private int maxPlayers = 0;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
}
