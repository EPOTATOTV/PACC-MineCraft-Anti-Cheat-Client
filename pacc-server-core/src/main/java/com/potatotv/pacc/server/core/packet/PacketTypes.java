package com.potatotv.pacc.server.core.packet;

import java.util.Set;

/**
 * 归一化数据包类型与字段键词表。
 *
 * <p>适配层把各平台的数据包（PacketEvents 包装、Nukkit 包对象、Geyser 翻译后的包）统一翻译成
 * 这里的类型名与 {@code fields} 键，检测项只认这套词表，不认任何平台类型。这样包级检测的
 * 阈值与判定逻辑只写一遍。
 *
 * <p>类型名沿用 Minecraft 协议的服务端接收方向（serverbound）取名，与 PacketEvents
 * {@code PacketType.Play.Client.*} 一一对应；事件驱动平台（Bukkit 事件、Nukkit 事件）没有真实
 * 数据包来源，只能合成 {@link #PLAYER_GROUND} / {@link #TRANSACTION} 这类占位类型，其能力差异
 * 由 {@link PacketCapabilityMatrix} 显式声明，不靠猜。
 */
public final class PacketTypes {

    // ---- 移动（PacketType.Play.Client.PLAYER_POSITION / PLAYER_POSITION_AND_ROTATION / PLAYER_ROTATION / PLAYER_FLYING）----
    public static final String PLAYER_POSITION = "PLAYER_POSITION";
    public static final String PLAYER_POSITION_AND_ROTATION = "PLAYER_POSITION_AND_ROTATION";
    public static final String PLAYER_ROTATION = "PLAYER_ROTATION";
    public static final String PLAYER_FLYING = "PLAYER_FLYING";

    // ---- 交互 / 战斗 ----
    public static final String PLAYER_DIGGING = "PLAYER_DIGGING";
    public static final String PLAYER_BLOCK_PLACEMENT = "PLAYER_BLOCK_PLACEMENT";
    public static final String INTERACT_ENTITY = "INTERACT_ENTITY";
    public static final String ANIMATION = "ANIMATION";

    // ---- 背包 / 事务 / 握手 ----
    public static final String WINDOW_CONFIRMATION = "WINDOW_CONFIRMATION";
    public static final String PONG = "PONG";
    public static final String TELEPORT_CONFIRM = "TELEPORT_CONFIRM";

    /**
     * 事件驱动平台合成的占位类型：由既有的 {@code deltaY} 字段约定承载落地判定，
     * 没有对应的真实数据包。保留是为了 Geyser / Nukkit 侧不回归。
     */
    public static final String PLAYER_GROUND = "PLAYER_GROUND";

    /** 合成类型：事务 ack（<1.17 的 WindowConfirmation 语义在部分平台上只能靠字段约定喂入）。 */
    public static final String TRANSACTION = "TRANSACTION";

    /** 合成类型：客户端品牌（{@code minecraft:brand} 载荷，LOGIN 阶段）。 */
    public static final String CLIENT_BRAND = "CLIENT_BRAND";

    /** 真实数据包来源（PacketEvents 桥）实际能喂进检测引擎的类型集合，共 10 类。 */
    public static final Set<String> REAL_PACKET_SOURCE_TYPES = Set.of(
            PLAYER_POSITION, PLAYER_POSITION_AND_ROTATION, PLAYER_ROTATION, PLAYER_FLYING,
            PLAYER_DIGGING, PLAYER_BLOCK_PLACEMENT, INTERACT_ENTITY, ANIMATION,
            PONG, TELEPORT_CONFIRM);

    /** 是否携带坐标（x/y/z 三个字段齐全才算）。 */
    public static boolean carriesPosition(String packetType) {
        return PLAYER_POSITION.equals(packetType) || PLAYER_POSITION_AND_ROTATION.equals(packetType);
    }

    // ---- 字段键 ----
    public static final String FIELD_X = "x";
    public static final String FIELD_Y = "y";
    public static final String FIELD_Z = "z";
    public static final String FIELD_YAW = "yaw";
    public static final String FIELD_PITCH = "pitch";
    public static final String FIELD_ON_GROUND = "onGround";
    public static final String FIELD_GAME_MODE = "gameMode";
    /** 竖向落差，仅事件驱动平台的占位类型使用（见 {@link #PLAYER_GROUND}）。 */
    public static final String FIELD_DELTA_Y = "deltaY";
    public static final String FIELD_TRANSACTION_ID = "id";
    public static final String FIELD_SEQUENCE = "sequence";
    public static final String FIELD_ACTION = "action";
    public static final String FIELD_TARGET_ID = "targetId";
    public static final String FIELD_CHANNEL = "channel";
    public static final String FIELD_BLOCK_X = "blockX";
    public static final String FIELD_BLOCK_Y = "blockY";
    public static final String FIELD_BLOCK_Z = "blockZ";

    private PacketTypes() {
    }
}
