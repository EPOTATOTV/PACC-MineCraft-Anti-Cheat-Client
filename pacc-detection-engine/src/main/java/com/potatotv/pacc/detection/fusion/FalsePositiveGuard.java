package com.potatotv.pacc.detection.fusion;

import java.util.List;

/**
 * 误报抑制（技术文档 §8.1 / §12）。
 *
 * <p>网络异常与插件干扰都会造成与作弊无关的检测偏差，按文档口径降权：
 * 网络异常 ×0.6，插件干扰 ×0.5。另外，创造模式 / 飞行权限豁免、延迟尖峰窗口
 * 会直接丢弃对应层的证据（物理预测与行为统计在上述状态下没有判据意义）。
 */
public final class FalsePositiveGuard {

    /** 网络异常降权系数。 */
    public static final double NETWORK_ANOMALY_FACTOR = 0.6;

    /** 插件干扰降权系数。 */
    public static final double PLUGIN_INTERFERENCE_FACTOR = 0.5;

    private FalsePositiveGuard() {
    }

    /**
     * 按环境上下文过滤证据：被豁免状态下产出的证据直接丢弃。
     *
     * <ul>
     *   <li>{@code creative=true} 或 {@code flightAllowed=true}：丢弃 L6 物理预测类证据
     *       （检测器 ID 以 {@code prediction-} 开头）与飞行/速度类行为证据</li>
     *   <li>{@code lagSpike=true}：丢弃 L5/L6 移动与包时序类证据</li>
     * </ul>
     */
    public static List<Evidence> filterExempted(EvidenceBatch batch) {
        boolean creative = batch.boolFlag("creative") || batch.boolFlag("flightAllowed");
        boolean lagSpike = batch.boolFlag("lagSpike");
        return batch.evidence().stream()
                .filter(ev -> !(creative && ev.layer() == Layer.BEHAVIOR
                        && (ev.detectorId().startsWith("prediction-") || isMovementLike(ev.detectorId()))))
                .filter(ev -> !(lagSpike && (ev.layer() == Layer.NETWORK || isMovementLike(ev.detectorId()))))
                .toList();
    }

    /**
     * 对融合总分应用降权系数（技术文档 §8.1：网络异常 ×0.6，插件干扰 ×0.5）。
     */
    public static double suppress(double score, EvidenceBatch batch) {
        double adjusted = score;
        if (batch.hasNetworkAnomaly()) {
            adjusted *= NETWORK_ANOMALY_FACTOR;
        }
        if (batch.hasPluginInterference()) {
            adjusted *= PLUGIN_INTERFERENCE_FACTOR;
        }
        return Math.max(0.0, Math.min(1.0, adjusted));
    }

    private static boolean isMovementLike(String detectorId) {
        return detectorId.startsWith("packet-timing")
                || detectorId.startsWith("prediction-")
                || detectorId.contains("movement");
    }
}
