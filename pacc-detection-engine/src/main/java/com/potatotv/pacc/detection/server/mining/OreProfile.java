package com.potatotv.pacc.detection.server.mining;

/**
 * 单个玩家的挖矿画像快照。
 *
 * @param totalOres       统计窗口内挖到的矿石总数（不含石头等普通方块）
 * @param rareOres        其中稀有矿数量（钻石 / 绿宝石 / 远古残骸）
 * @param travelledBlocks 统计窗口内玩家水平移动的方块数；{@code <= 0} 表示没有移动数据
 */
public record OreProfile(int totalOres, int rareOres, double travelledBlocks) {

    /** 稀有矿占比；没有矿石时返回 0。 */
    public double rareRatio() {
        return totalOres <= 0 ? 0.0 : (double) rareOres / totalOres;
    }

    /** 每挖到一块矿平均要跑多少格；没有矿石时返回 {@link Double#MAX_VALUE}。 */
    public double blocksPerOre() {
        return totalOres <= 0 ? Double.MAX_VALUE : travelledBlocks / totalOres;
    }

    /** 是否有可用的移动数据。 */
    public boolean hasTravelData() {
        return travelledBlocks > 0;
    }
}
