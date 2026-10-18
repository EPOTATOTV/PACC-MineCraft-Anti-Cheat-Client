package com.potatotv.pacc.detection.server.mining;

/**
 * 视角-矿石相关性画像（P-04，借鉴 ADSAnitiray 的 ViewAnalysis 思路）。
 *
 * <p>X-Ray 玩家挖矿时视角几乎总是正对目标矿，且挖矿方向高度集中；正常玩家会四处看、
 * 调整站位、绕过岩浆，视角分布更散。这里把三个统计量交给 {@link XRayAnalyzer} 与
 * 稀有矿占比、路径一致性交叉验证，单项不单独出证。
 *
 * @param samples          有效矿石样本数（缺视角或位置数据的挖掘不计入）
 * @param meanAlignmentDeg 视角与「玩家→矿石」方向的平均夹角（越小越像正对目标）
 * @param alignmentIqrDeg  夹角的四分位距（越小说明姿势越机械）
 * @param yawEntropyBits   挖矿瞬间 yaw 分布的香农熵（越小说明方向越集中）
 * @param pathStraightness 会话内水平位移直线度：净位移 / 累计路程，取值 [0,1]
 */
public record ViewCorrelationProfile(
        int samples,
        double meanAlignmentDeg,
        double alignmentIqrDeg,
        double yawEntropyBits,
        double pathStraightness) {

    public static ViewCorrelationProfile empty() {
        return new ViewCorrelationProfile(0, 0, 0, 0, 0);
    }

    /** 是否有足够的视角样本参与判定。 */
    public boolean hasViewData() {
        return samples > 0;
    }
}
