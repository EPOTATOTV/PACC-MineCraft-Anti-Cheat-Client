package com.potatotv.pacc.server.core.packet;

import com.potatotv.pacc.server.core.model.ServerType;

import java.util.List;
import java.util.Set;

/**
 * 平台包级能力矩阵（技术文档 §5.1 U-03）。
 *
 * <p>只做一件事：把「这个平台到底能做包级检测到什么程度」变成可查询、可打印、可测试的事实，
 * 而不是散落在适配层注释里。适配层启动时按本矩阵声明自己的能力，运维看日志或看 README 的
 * 矩阵表就知道为什么某个平台上没有 Timer 告警。
 */
public final class PacketCapabilityMatrix {

    /** 事件驱动平台能合成的占位类型：只有落地判定与事务 ack，没有包到达时序。 */
    private static final Set<String> EVENT_DRIVEN_TYPES =
            Set.of(PacketTypes.PLAYER_GROUND, PacketTypes.TRANSACTION);

    private static final String BUKKIT_NO_PACKET_EVENTS =
            "未检测到 PacketEvents，退回 Bukkit 事件口径：Timer/Blink/Phase 不可用，"
                    + "NO_FALL 只能靠合成 deltaY 字段，包序号与事务同步缺失";

    private static final String BUKKIT_WITH_PACKET_EVENTS =
            "PacketEvents 已接入，Netty 线程解析移动/挖掘/放置/交互/挥动/事务包，"
                    + "Timer/Blink/事务同步按真实包到达时序判定";

    private static final String BEDROCK_EVENT_DRIVEN =
            "基岩系服务端无 PacketEvents 等价物，只有事件模型：包级时序与序列断言不可用";

    private static final String GEYSER_HOST_MOUNTED =
            "Geyser 是协议桥不是服务端，包级能力随宿主形态；跨代理线索统一走 PTV 通道，不做本地包断言";

    private PacketCapabilityMatrix() {
    }

    /** 按平台与运行时条件推导能力。 */
    public static PacketCapability forPlatform(
            ServerType platform, boolean packetEventsActive, boolean blockProbeAvailable) {
        if (platform == null) {
            return new PacketCapability(ServerType.SPIGOT, false, "unknown", EVENT_DRIVEN_TYPES,
                    false, "平台类型未知，按最低能力声明");
        }
        return switch (platform) {
            case PAPER, SPIGOT -> packetEventsActive
                    ? new PacketCapability(platform, true, "packetevents-2.x",
                            PacketTypes.REAL_PACKET_SOURCE_TYPES, blockProbeAvailable,
                            BUKKIT_WITH_PACKET_EVENTS)
                    : new PacketCapability(platform, false, "bukkit-events", EVENT_DRIVEN_TYPES,
                            blockProbeAvailable, BUKKIT_NO_PACKET_EVENTS);
            case NUKKIT, NUKKIT_MOT, ALLAYMC -> new PacketCapability(platform, false,
                    "platform-events", EVENT_DRIVEN_TYPES, blockProbeAvailable, BEDROCK_EVENT_DRIVEN);
            case GEYSER -> new PacketCapability(platform, false, "geyser-host-proxy",
                    EVENT_DRIVEN_TYPES, false, GEYSER_HOST_MOUNTED);
        };
    }

    /**
     * 全平台能力快照，取「能接入的最强形态」作为上界（PacketEvents 可用、方块探针可用），
     * 用于文档矩阵表与能力回归测试。
     */
    public static List<PacketCapability> ceiling() {
        return List.of(
                forPlatform(ServerType.PAPER, true, true),
                forPlatform(ServerType.SPIGOT, true, true),
                forPlatform(ServerType.NUKKIT, false, true),
                forPlatform(ServerType.NUKKIT_MOT, false, true),
                forPlatform(ServerType.ALLAYMC, false, true),
                forPlatform(ServerType.GEYSER, false, false));
    }

    /** 多行能力报告，适配层启动时打一条。 */
    public static String report(PacketCapability capability) {
        StringBuilder sb = new StringBuilder(160);
        sb.append("包级检测能力：").append(capability.describe());
        if (!capability.realPacketSource()) {
            sb.append("。该平台的包级检测降级为事件口径，这一条不是故障");
        }
        return sb.toString();
    }
}
