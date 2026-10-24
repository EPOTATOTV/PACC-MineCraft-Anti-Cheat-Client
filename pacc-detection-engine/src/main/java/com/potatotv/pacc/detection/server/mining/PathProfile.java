package com.potatotv.pacc.detection.server.mining;

/**
 * 挖矿路径画像（R-10）。
 *
 * <p>只看「稀有矿占比 + 每矿探索距离」容易被绕路式挖矿稀释。路径画像补一个维度：
 * 透视玩家通常沿一条直线直插矿点，很少转向；正常探索会反复调整方向、绕岩浆、回身。</p>
 *
 * @param travelledBlocks       统计窗口内累计水平路程（格）
 * @param turnRate              转向率：相邻位移方向夹角超过 45° 的次数 / 方向样本数
 * @param longestStraightBlocks 最长一段连续同向路径长度（格）
 */
public record PathProfile(double travelledBlocks, double turnRate, double longestStraightBlocks) {

    public static PathProfile empty() {
        return new PathProfile(0, 0, 0);
    }

    public boolean hasPathData() {
        return travelledBlocks > 0;
    }
}
