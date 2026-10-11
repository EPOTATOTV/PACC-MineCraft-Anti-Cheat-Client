package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;

import java.util.List;

/**
 * 服务端平台适配 SPI。每个平台（Paper/Spigot/Nukkit/Nukkit-MOT/AllayMC/Geyser）实现一份薄适配。
 * 实现方负责从平台事件里取数据、把告警与处置落成平台侧行为。
 */
public interface ServerPlatformAdapter {

    ServerType serverType();

    String serverId();

    String serverName();

    int maxPlayers();

    String serverVersion();

    PlayerPosition getPlayerPosition(String playerId);

    GameMode getGameMode(String playerId);

    String getClientBrand(String playerId);

    List<String> getInstalledMods(String playerId);

    List<AddonInfo> getInstalledAddons(String playerId);

    void alert(String playerId, String message);

    void punish(String playerId, PunishmentType type, String reason, long durationMs);
}
