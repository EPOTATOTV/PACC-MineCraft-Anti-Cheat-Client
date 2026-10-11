package com.potatotv.pacc.client.core.model;

/**
 * 单次移动采样。由加载器层从游戏循环里取出本地玩家位置后交给检测器。
 */
public record PlayerMovement(
        double x,
        double y,
        double z,
        boolean onGround,
        GameMode gameMode,
        long timestampMs) {

    public PlayerMovement {
        if (gameMode == null) {
            gameMode = GameMode.SURVIVAL;
        }
    }

    /** 与另一次采样之间的水平距离。 */
    public double horizontalDistanceTo(PlayerMovement other) {
        double dx = other.x - x;
        double dz = other.z - z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
