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

    /** 视角相关性判定所需的最小矿石样本数（比基础判定低一档，作为补充维度）。 */
    public static final int MIN_VIEW_SAMPLES = 20;

    /** 稀有矿占比阈值（设计初值，待真实数据校准）。 */
    public static final double RARE_RATIO_THRESHOLD = 0.35;

    /** 视角补充判定使用的稀有矿占比下限：低于基础阈值，但也不能是普通矿工。 */
    public static final double VIEW_RARE_RATIO_MIN = 0.245;

    /** 每块矿对应的探索距离下限（格）；低于该值说明几乎没走路就挖到矿。 */
    public static final double BLOCKS_PER_ORE_MIN = 8.0;

    /** 视角平均正对角度上限（度）。 */
    public static final double VIEW_MEAN_ALIGN_MAX_DEG = 20.0;

    /** 视角正对角度 IQR 上限（度）：越小说明姿势越机械。 */
    public static final double VIEW_ALIGN_IQR_MAX_DEG = 15.0;

    /** 挖矿 yaw 分布熵上限（bit）：低于该值说明方向高度集中。 */
    public static final double VIEW_YAW_ENTROPY_MAX_BITS = 1.5;

    /** 路径直线度下限：接近 1 说明几乎沿直线直奔矿点。 */
    public static final double VIEW_PATH_STRAIGHT_MIN = 0.75;

    /** 路径分析所需的最小累计路程（格），太短不判。 */
    public static final double PATH_MIN_TRAVEL_BLOCKS = 128.0;

    /** 路径转向率上限：越低越像沿直线直插。 */
    public static final double PATH_MAX_TURN_RATE = 0.15;

    /** 最长同向直线段下限（格）。 */
    public static final double PATH_MIN_STRAIGHT_BLOCKS = 96.0;

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

    /**
     * P-04 视角相关性补充判定（借鉴 ADSAnitiray ViewAnalysis）。
     *
     * <p>触发需要同时满足：
     * <ol>
     *   <li>稀有矿占比不低于 {@value #VIEW_RARE_RATIO_MIN}（排除普通矿工）</li>
     *   <li>挖矿时视角持续正对目标：平均夹角 ≤ {@value #VIEW_MEAN_ALIGN_MAX_DEG}，
     *       且 IQR ≤ {@value #VIEW_ALIGN_IQR_MAX_DEG}（姿势机械）</li>
     *   <li>视角方向高度集中（熵 &lt; {@value #VIEW_YAW_ENTROPY_MAX_BITS}）或路径近乎直线
     *       （直线度 ≥ {@value #VIEW_PATH_STRAIGHT_MIN}）</li>
     * </ol>
     *
     * <p>这是补充维度：视角数据缺失、样本不足或稀有矿占比不够时直接放弃，
     * 不与基础判定叠加成更高的置信度，避免把两项软证据相乘成误报。
     */
    public Optional<Evidence> evaluateViewCorrelation(ViewCorrelationProfile view, OreProfile ore) {
        if (view == null || ore == null
                || view.samples() < MIN_VIEW_SAMPLES
                || ore.totalOres() < MIN_VIEW_SAMPLES
                || ore.rareRatio() < VIEW_RARE_RATIO_MIN) {
            return Optional.empty();
        }
        boolean aimed = view.meanAlignmentDeg() <= VIEW_MEAN_ALIGN_MAX_DEG
                && view.alignmentIqrDeg() <= VIEW_ALIGN_IQR_MAX_DEG;
        boolean concentrated = view.yawEntropyBits() < VIEW_YAW_ENTROPY_MAX_BITS;
        boolean straight = view.pathStraightness() >= VIEW_PATH_STRAIGHT_MIN;
        if (!aimed || !(concentrated || straight)) {
            return Optional.empty();
        }
        double alignMargin = clamp((VIEW_MEAN_ALIGN_MAX_DEG - view.meanAlignmentDeg())
                / VIEW_MEAN_ALIGN_MAX_DEG);
        double iqrMargin = clamp((VIEW_ALIGN_IQR_MAX_DEG - view.alignmentIqrDeg())
                / VIEW_ALIGN_IQR_MAX_DEG);
        double confidence = Math.min(MAX_CONFIDENCE, 0.55 + 0.2 * alignMargin + 0.15 * iqrMargin);
        String detail = String.format(Locale.ROOT,
                "X-Ray 视角相关性 正对角度均值=%.1f° IQR=%.1f° yaw熵=%.2fbit 直线度=%.2f 稀有矿占比=%.2f 样本=%d",
                view.meanAlignmentDeg(), view.alignmentIqrDeg(), view.yawEntropyBits(),
                view.pathStraightness(), ore.rareRatio(), view.samples());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, confidence, detail));
    }

    /**
     * R-10 路径行为补充判定：沿长直线直插矿点、几乎不转向。
     *
     * <p>只在前两项都没出证时兜底。要求稀有矿占比达标、每矿探索距离低于下限，
     * 再叠加「转向率低 + 最长同向段长」。正常玩家挖直线矿道也可能命中这一段，
     * 所以额外要求每矿探索距离足够短，把「效率高」和「透视」区分开。</p>
     */
    public Optional<Evidence> evaluatePath(PathProfile path, OreProfile ore) {
        if (path == null || ore == null || !path.hasPathData()) {
            return Optional.empty();
        }
        if (ore.totalOres() < MIN_VIEW_SAMPLES || ore.rareRatio() < VIEW_RARE_RATIO_MIN) {
            return Optional.empty();
        }
        if (path.travelledBlocks() < PATH_MIN_TRAVEL_BLOCKS
                || path.turnRate() > PATH_MAX_TURN_RATE
                || path.longestStraightBlocks() < PATH_MIN_STRAIGHT_BLOCKS
                || ore.blocksPerOre() > BLOCKS_PER_ORE_MIN) {
            return Optional.empty();
        }
        double straightMargin = clamp((path.longestStraightBlocks() - PATH_MIN_STRAIGHT_BLOCKS)
                / PATH_MIN_STRAIGHT_BLOCKS);
        double turnMargin = clamp((PATH_MAX_TURN_RATE - path.turnRate()) / PATH_MAX_TURN_RATE);
        double confidence = Math.min(MAX_CONFIDENCE, 0.55 + 0.2 * straightMargin + 0.15 * turnMargin);
        String detail = String.format(Locale.ROOT,
                "X-Ray 路径相关性 转向率=%.2f（上限 %.2f） 最长直线=%.0f格（下限 %.0f） 每矿距离=%.2f格 样本=%d",
                path.turnRate(), PATH_MAX_TURN_RATE, path.longestStraightBlocks(),
                PATH_MIN_STRAIGHT_BLOCKS, ore.blocksPerOre(), ore.totalOres());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, confidence, detail));
    }
}
