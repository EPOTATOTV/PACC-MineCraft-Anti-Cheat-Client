package com.potatotv.pacc.detection.fusion;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 融合引擎总入口（技术文档 §8.1）。
 *
 * <p>流程：误报豁免过滤 → 层内 noisy-OR 聚合 → 跨层加权 → 环境降权 → 分档。
 * 分档阈值：总分 &gt; 0.9 判 CONFIRMED（红屏+查端），&gt; 0.7 判 SUSPECT（观察+记录），否则 CLEAN。
 * 玩家端模式不封禁：CONFIRMED 只触发红屏警告与留痕，处置由部署形态决定。
 */
public final class FusionEngine {

    /** 融合结果：总分、各层分、最终结论与被抑制系数。 */
    public record FusionResult(double score, Map<Layer, Double> layerScores, Verdict verdict,
                               boolean networkSuppressed, boolean pluginSuppressed) {
    }

    /** CONFIRMED 阈值。 */
    public static final double CONFIRMED_THRESHOLD = 0.9;

    /** SUSPECT 阈值。 */
    public static final double SUSPECT_THRESHOLD = 0.7;

    private final MultiLayerFusion fusion = new MultiLayerFusion();
    private Map<Layer, Double> weights;

    public FusionEngine() {
        this.weights = ConfidenceScorer.defaultLayerWeights();
    }

    /** 覆盖某层权重（如建服场景调低行为层灵敏度）。 */
    public synchronized void setWeight(Layer layer, double weight) {
        weights.put(layer, Math.max(0.0, Math.min(1.0, weight)));
    }

    /** 当前权重快照。 */
    public synchronized Map<Layer, Double> weights() {
        return new EnumMap<>(weights);
    }

    /** 融合判定：返回带明细的结果。 */
    public FusionResult evaluateDetailed(EvidenceBatch batch) {
        List<Evidence> filtered = FalsePositiveGuard.filterExempted(batch);
        MultiLayerFusion.LayerScores layerScores = fusion.aggregate(filtered);
        double raw = fusion.weightedSum(layerScores, weights);
        boolean netSup = batch.hasNetworkAnomaly();
        boolean plugSup = batch.hasPluginInterference();
        double score = FalsePositiveGuard.suppress(raw, batch);
        Verdict verdict;
        if (score > CONFIRMED_THRESHOLD) {
            verdict = Verdict.CONFIRMED;
        } else if (score > SUSPECT_THRESHOLD) {
            verdict = Verdict.SUSPECT;
        } else {
            verdict = Verdict.CLEAN;
        }
        return new FusionResult(score, layerScores.scores(), verdict, netSup, plugSup);
    }

    /** 融合判定：只返回结论（技术文档 §8.1 示例口径）。 */
    public Verdict evaluate(EvidenceBatch batch) {
        return evaluateDetailed(batch).verdict();
    }
}
