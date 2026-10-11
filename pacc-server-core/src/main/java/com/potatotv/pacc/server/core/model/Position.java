package com.potatotv.pacc.server.core.model;

/**
 * 世界坐标三元组。
 */
public record Position(double x, double y, double z) {

    /** 欧氏距离。 */
    public double distanceTo(Position o) {
        double dx = x - o.x;
        double dy = y - o.y;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /** 水平距离（忽略 y 轴）。 */
    public double horizontalDistanceTo(Position o) {
        double dx = x - o.x;
        double dz = z - o.z;
        return Math.sqrt(dx * dx + dz * dz);
    }
}
