package com.potatotv.pacc.detection.server.prediction;

/**
 * 简单方块 / 实体碰撞箱（轴对齐，单位：格）。
 *
 * <p>供世界副本与命中校验做点-箱包含判断；坐标为世界坐标，边界闭区间。
 */
public record BlockBox(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {

    public BlockBox {
        if (minX > maxX || minY > maxY || minZ > maxZ) {
            throw new IllegalArgumentException("非法碰撞箱: min > max");
        }
    }

    /** 点是否在箱内（含边界）。 */
    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** 各方向外扩 amount（负值收缩）。 */
    public BlockBox expand(double amount) {
        return new BlockBox(minX - amount, minY - amount, minZ - amount,
                maxX + amount, maxY + amount, maxZ + amount);
    }
}
