package com.potatotv.pacc.server.core.model;

/**
 * 服务端自身信息，注册上报时使用。
 */
public record ServerInfo(
        String serverId,
        ServerType serverType,
        String pluginVersion,
        String serverVersion,
        String serverName,
        int maxPlayers) {
}
