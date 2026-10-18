package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerType;
import com.potatotv.pacc.server.core.packet.BlockProbe;
import com.potatotv.pacc.server.core.packet.PacketCapability;
import com.potatotv.pacc.server.core.packet.PacketCapabilityMatrix;
import com.potatotv.pacc.server.core.packet.TransactionSender;

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

    /**
     * 客户端通过 {@code pacc:mods} 通道上报的 Mod 文件哈希（小写 sha256）。默认无哈希来源，
     * 只有实现了该通道接收的平台才覆写。
     */
    default List<String> getInstalledModHashes(String playerId) {
        return List.of();
    }

    List<AddonInfo> getInstalledAddons(String playerId);

    /**
     * 方块通行性探针，穿墙（Phase）检测用。默认返回 {@code null} 表示平台给不出方块数据，
     * 此时 Phase 检查整体关闭，并由 {@link #packetCapability()} 声明「穿墙检测不可用」。
     */
    default BlockProbe blockProbe() {
        return null;
    }

    /**
     * 事务发包接缝。默认返回 {@code null} 表示平台发不出事务包，此时延迟补偿与 Disabler 判定
     * 放弃该玩家（不记账、不告警），而不是拿编造的 id 制造 ack 率为零的假阳性。
     */
    default TransactionSender transactionSender() {
        return null;
    }

    /** 本平台的包级检测能力声明，启动日志与能力矩阵表都用它。 */
    default PacketCapability packetCapability() {
        return PacketCapabilityMatrix.forPlatform(serverType(), false, blockProbe() != null);
    }

    void alert(String playerId, String message);

    void punish(String playerId, PunishmentType type, String reason, long durationMs);
}
