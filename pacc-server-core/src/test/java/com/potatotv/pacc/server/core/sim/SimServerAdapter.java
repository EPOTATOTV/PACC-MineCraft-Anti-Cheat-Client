package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.ServerPlatformAdapter;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;

import java.util.ArrayList;
import java.util.List;

/**
 * 模拟平台适配层：只提供检测引擎需要的最小数据，把 {@code alert}/{@code punish} 记录到内存，
 * 供离线模拟断言「零告警、零处置」。不连接任何真实服务器。
 */
final class SimServerAdapter implements ServerPlatformAdapter {

    private final List<String> alerts = new ArrayList<>();
    private final List<String> punishments = new ArrayList<>();

    List<String> alerts() {
        return alerts;
    }

    List<String> punishments() {
        return punishments;
    }

    @Override
    public ServerType serverType() {
        return ServerType.PAPER;
    }

    @Override
    public String serverId() {
        return "sim-server";
    }

    @Override
    public String serverName() {
        return "Sim Server";
    }

    @Override
    public int maxPlayers() {
        return 100;
    }

    @Override
    public String serverVersion() {
        return "1.21";
    }

    @Override
    public PlayerPosition getPlayerPosition(String playerId) {
        return null;
    }

    @Override
    public GameMode getGameMode(String playerId) {
        return GameMode.SURVIVAL;
    }

    @Override
    public String getClientBrand(String playerId) {
        return "vanilla";
    }

    @Override
    public List<String> getInstalledMods(String playerId) {
        return List.of();
    }

    @Override
    public List<AddonInfo> getInstalledAddons(String playerId) {
        return List.of();
    }

    @Override
    public void alert(String playerId, String message) {
        alerts.add(message);
    }

    @Override
    public void punish(String playerId, PunishmentType type, String reason, long durationMs) {
        punishments.add(type.name());
    }
}
