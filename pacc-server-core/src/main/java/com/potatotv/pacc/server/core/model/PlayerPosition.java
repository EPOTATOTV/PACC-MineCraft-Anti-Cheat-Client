package com.potatotv.pacc.server.core.model;

/**
 * 某一时刻的玩家位置与朝向快照。
 */
public record PlayerPosition(
        String playerId,
        Position pos,
        float yaw,
        float pitch,
        long timestampMs,
        boolean onGround,
        GameMode gameMode) {
}
