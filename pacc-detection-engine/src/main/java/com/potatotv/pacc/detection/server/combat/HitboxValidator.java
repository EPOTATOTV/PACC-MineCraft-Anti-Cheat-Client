package com.potatotv.pacc.detection.server.combat;

import java.util.Locale;
import java.util.Optional;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.server.prediction.Position;

/**
 * 命中箱校验（技术文档 §7.3 / §7.4 跨维度联合的战斗侧输入）。
 *
 * <p>以攻击方视线方向（yaw）构造水平射线，计算射线到受击方 AABB 的最近距离；
 * 最近距离超过命中箱对角半径（width/2·√2 + 0.1 浮点容忍）时，
 * 视线在几何上不可能命中该受击方 → Hitbox 作弊证据。
 *
 * <p>数学：视线单位向量 = (-sin(yaw), 0, cos(yaw))（MC 坐标系 yaw=0 朝 +Z）。
 * 射线到 AABB 的最近距离 = min over t≥0 |ray(t) - clamp(ray(t), box)|，
 * 目标函数对 t 凸（点到凸集距离的仿射复合），用三分搜索求最小值。
 * 跨维度联合（攻击模式 + 旋转 + 命中）由 CorrelationEngine 合并。
 */
public final class HitboxValidator {

    public static final String DETECTOR_ID = "combat-hitbox";

    /** 距离阈值外的浮点容忍（格）。 */
    private static final double EPSILON = 0.1;

    /** 不可能命中固定置信。 */
    private static final double CONFIDENCE = 0.85;

    /** 三分搜索迭代次数：范围 ≤ 数百格时精度远优于 1e-6。 */
    private static final int SEARCH_ITERATIONS = 100;

    /**
     * @param attackerId    仅进入 detail（本检测无跨 tick 状态）
     * @param eyeHeight     攻击方眼高（上下文冗余参数，供版本差异与日志对齐使用）
     * @param eyePos        攻击方眼睛位置（射线起点）
     * @param yawDeg        攻击方视线 yaw（度，MC 口径）
     * @param victimPos     受击方脚部位置（AABB 底面中心）
     * @param victimWidth   受击方碰撞箱宽度
     * @param victimHeight  受击方碰撞箱高度
     */
    public Optional<Evidence> check(String attackerId, double eyeHeight, Position eyePos, float yawDeg,
                                    Position victimPos, double victimWidth, double victimHeight) {
        double halfWidth = victimWidth / 2.0;
        double minX = victimPos.x() - halfWidth;
        double maxX = victimPos.x() + halfWidth;
        double minZ = victimPos.z() - halfWidth;
        double maxZ = victimPos.z() + halfWidth;
        double minY = victimPos.y();
        double maxY = victimPos.y() + victimHeight;

        double rad = Math.toRadians(yawDeg);
        double dx = -Math.sin(rad);
        double dy = 0.0;
        double dz = Math.cos(rad);

        double nearest = distanceRayToBox(eyePos.x(), eyePos.y(), eyePos.z(), dx, dy, dz,
                minX, minY, minZ, maxX, maxY, maxZ);
        double threshold = halfWidth * Math.sqrt(2.0) + EPSILON;
        if (nearest <= threshold) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "不可能命中 玩家=%s 射线到命中箱最近距离=%.3f 阈值=%.3f 眼高=%.2f",
                attackerId, nearest, threshold, eyeHeight);
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, CONFIDENCE, detail));
    }

    /**
     * 射线到 AABB 的最近距离：f(t) = |O+tD - clamp(O+tD, box)| 在 t≥0 上为凸函数，
     * 三分搜索最小值。
     */
    private static double distanceRayToBox(double ox, double oy, double oz,
                                           double dx, double dy, double dz,
                                           double minX, double minY, double minZ,
                                           double maxX, double maxY, double maxZ) {
        // 搜索上界：起点到包围盒最远角点的距离
        double farCornerX = dx >= 0 ? maxX : minX;
        double farCornerY = dy >= 0 ? maxY : minY;
        double farCornerZ = dz >= 0 ? maxZ : minZ;
        double hi = Math.sqrt(square(farCornerX - ox) + square(farCornerY - oy) + square(farCornerZ - oz));
        double lo = 0.0;
        for (int i = 0; i < SEARCH_ITERATIONS; i++) {
            double third = (hi - lo) / 3.0;
            double m1 = lo + third;
            double m2 = hi - third;
            if (boxDistance(ox + dx * m1, oy + dy * m1, oz + dz * m1, minX, minY, minZ, maxX, maxY, maxZ)
                    < boxDistance(ox + dx * m2, oy + dy * m2, oz + dz * m2, minX, minY, minZ, maxX, maxY, maxZ)) {
                hi = m2;
            } else {
                lo = m1;
            }
        }
        double t = (lo + hi) / 2.0;
        return boxDistance(ox + dx * t, oy + dy * t, oz + dz * t, minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** 点到 AABB 的距离（把点夹到盒内后取欧氏距离）。 */
    private static double boxDistance(double px, double py, double pz,
                                      double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ) {
        double cx = Math.max(minX, Math.min(maxX, px));
        double cy = Math.max(minY, Math.min(maxY, py));
        double cz = Math.max(minZ, Math.min(maxZ, pz));
        return Math.sqrt(square(px - cx) + square(py - cy) + square(pz - cz));
    }

    private static double square(double v) {
        return v * v;
    }
}
