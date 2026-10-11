package com.potatotv.pacc.server.core.model;

/**
 * 一次攻击事件。
 *
 * @param viewAngleDeg 攻击者朝向与指向目标方向之间的夹角（度）。
 */
public record CombatEvent(
        String attackerId,
        String targetId,
        Position attackerPos,
        Position targetPos,
        long timestampMs,
        boolean critical,
        double viewAngleDeg) {
}
