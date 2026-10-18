package com.potatotv.pacc.server.core.packet;

/**
 * 方块通行性探针：穿墙（Phase）检测需要知道目标位置的方块是否可穿过。
 *
 * <p>三态而不是布尔：平台可能根本给不出方块数据（事件驱动平台、没加载的区块、跨代理场景）。
 * 这时必须返回 {@link SolidState#UNKNOWN}，让检测项跳过而不是把「查不了」当成「可穿过」——
 * 后者会把所有正常玩家判成穿墙。
 */
public interface BlockProbe {

    enum SolidState {
        /** 实心方块，玩家不应停在其中。 */
        SOLID,
        /** 可穿过（空气、水、草丛等）。 */
        PASSABLE,
        /** 该位置拿不到方块数据，不得据此下结论。 */
        UNKNOWN
    }

    /** 查询某个方块坐标的通行性。实现须线程安全。 */
    SolidState stateAt(String playerId, int blockX, int blockY, int blockZ);
}
