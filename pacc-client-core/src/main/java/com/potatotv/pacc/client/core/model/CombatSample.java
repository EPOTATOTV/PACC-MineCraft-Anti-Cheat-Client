package com.potatotv.pacc.client.core.model;

/**
 * 单次攻击采样。{@code viewAngleDeg} 是攻击者视线与目标方向的夹角（度）。
 */
public record CombatSample(
        String targetId,
        double attackerX,
        double attackerY,
        double attackerZ,
        double targetX,
        double targetY,
        double targetZ,
        double viewAngleDeg,
        long timestampMs) {

    /** 攻击者与目标的直线距离。 */
    public double reach() {
        double dx = targetX - attackerX;
        double dy = targetY - attackerY;
        double dz = targetZ - attackerZ;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }
}
