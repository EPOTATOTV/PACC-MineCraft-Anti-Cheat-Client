package com.potatotv.pacc.server.core.model;

/**
 * 某一时刻的玩家位置与朝向快照。
 *
 * @param world 世界名；平台拿不到时为 {@code "world"}。预测检查用它把玩家绑定到共享世界副本。
 */
public record PlayerPosition(
        String playerId,
        Position pos,
        float yaw,
        float pitch,
        long timestampMs,
        boolean onGround,
        GameMode gameMode,
        String world) {

    /** 兼容旧调用：没有世界名时归到默认世界。 */
    public PlayerPosition(String playerId, Position pos, float yaw, float pitch,
                          long timestampMs, boolean onGround, GameMode gameMode) {
        this(playerId, pos, yaw, pitch, timestampMs, onGround, gameMode, "world");
    }
}
