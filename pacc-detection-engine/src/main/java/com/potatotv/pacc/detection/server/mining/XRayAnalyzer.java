package com.potatotv.pacc.detection.server.mining;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

import java.util.Locale;
import java.util.Optional;

/**
 * X-Ray 挖矿统计判定（技术文档 §5.3 U-04）。
 *
 * <p>判定不用「挖了多少矿」这一个数字——正常玩家在矿洞里也可能挖很多。X-Ray 的特征是
 * **稀有矿占比异常高**且**每块矿对应的探索距离异常短**：透视直接朝矿走，不会绕路。
 * 两个条件同时成立才出证，单条命中只记不报，压误报。
 *
 * <p>阈值是设计初值，需要用真实对局数据校准；在校准之前宁可保守（样本量门槛 + 双条件）。
 * 这不是「拍脑袋的精确值」，代码里把它标成待校准，别当成结论。
 *
 * <p>没有移动数据时直接放弃判定：只看稀有矿占比会把在地牢里连挖三颗钻石的正常玩家误伤。
 */
public final class XRayAnalyzer {

    public static final String DETECTOR_ID = "xray-mining";

    /** 样本量门槛：低于该矿石数不判，避免开局几块矿就下结论。 */
    public static final int MIN_ORES = 30;

    /** 稀有矿占比阈值（设计初值，待真实数据校准）。 */
    public static final double RARE_RATIO_THRESHOLD = 0.35;

    /** 每块矿对应的探索距离下限（格）；低于该值说明几乎没走路就挖到矿。 */
    public static final double BLOCKS_PER_ORE_MIN = 8.0;

    /** 置信度上限：统计类证据不给满值，留给融合层与人工复核。 */
    private static final double MAX_CONFIDENCE = 0.95;

    /**
     * 评估一个挖矿画像。
     *
     * @return 命中时返回证据；样本不足、缺少移动数据、或只满足单一条件时返回空
     */
    public Optional<Evidence> evaluate(OreProfile profile) {
        if (profile == null || profile.totalOres() < MIN_ORES || !profile.hasTravelData()) {
            return Optional.empty();
        }
        double rareRatio = profile.rareRatio();
        double blocksPerOre = profile.blocksPerOre();
        if (rareRatio < RARE_RATIO_THRESHOLD || blocksPerOre > BLOCKS_PER_ORE_MIN) {
            return Optional.empty();
        }
        double ratioMargin = clamp((rareRatio - RARE_RATIO_THRESHOLD) / (1.0 - RARE_RATIO_THRESHOLD));
        double pathMargin = clamp((BLOCKS_PER_ORE_MIN - blocksPerOre) / BLOCKS_PER_ORE_MIN);
        double confidence = Math.min(MAX_CONFIDENCE, 0.5 + 0.25 * ratioMargin + 0.2 * pathMargin);
        String detail = String.format(Locale.ROOT,
                "X-Ray 疑似 稀有矿占比=%.2f（阈值 %.2f） 每矿探索距离=%.2f格（下限 %.1f） 样本=%d",
                rareRatio, RARE_RATIO_THRESHOLD, blocksPerOre, BLOCKS_PER_ORE_MIN, profile.totalOres());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, confidence, detail));
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
