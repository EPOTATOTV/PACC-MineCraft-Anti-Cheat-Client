package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.BehaviorProfile;
import com.potatotv.pacc.detection.profile.ClickProfile;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * L4 输入层检测器（技术文档 &sect;6.4 输入分布分析）。
 *
 * <p>数据不来自 {@code OsProbe}，而是本机键盘钩子 / 鼠标事件流经
 * {@link #recordClick(long)} 喂入 30 秒滚动窗口（&sect;5.1：窗口化采集避免单次误判）。
 * {@link LocalDetector#scan} 只负责按窗口产出画像：robotic 判定出
 * {@link ClickProfile#machineLikelihood()} 置信证据，GCD 倍数模式追加 0.6 置信证据；
 * 窗口画像同步喂入 {@link BehaviorProfile} 供会话级行为统计。
 */
public final class InputAnalyzer implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(InputAnalyzer.class.getName());

    /** 点击滚动窗口长度。 */
    private static final long WINDOW_MS = 30_000;

    /** 画像产出间隔（每 30 秒窗口一次）。 */
    private static final long ANALYZE_INTERVAL_MS = 30_000;

    private static final double GCD_EVIDENCE_CONFIDENCE = 0.6;

    private final BehaviorProfile behavior;
    private final ArrayDeque<Long> clicks = new ArrayDeque<>();
    private long lastAnalyzeAt;
    private boolean hasNewData;

    public InputAnalyzer() {
        this(null);
    }

    /** 外部传入会话画像（复用全局 {@link BehaviorProfile}），null 则内部自建。 */
    public InputAnalyzer(BehaviorProfile behavior) {
        this.behavior = behavior == null ? new BehaviorProfile() : behavior;
    }

    /** 会话画像（含本检测器喂入的点击窗口）。 */
    public BehaviorProfile behavior() {
        return behavior;
    }

    /** 喂入一次本机采集的点击事件时间戳。 */
    public synchronized void recordClick(long timestampMs) {
        clicks.addLast(timestampMs);
        long cutoff = timestampMs - WINDOW_MS;
        while (!clicks.isEmpty() && clicks.peekFirst() < cutoff) {
            clicks.pollFirst();
        }
        hasNewData = true;
    }

    @Override
    public String id() {
        return "input-analyzer";
    }

    @Override
    public Layer layer() {
        return Layer.INPUT;
    }

    @Override
    public synchronized List<Evidence> scan(LocalScanContext ctx) {
        if (!hasNewData) {
            return List.of();
        }
        return analyze(ctx == null ? "" : ctx.playerId());
    }

    /** 对当前窗口产出点击画像证据；30 秒节流，窗口样本不足返回空。 */
    public synchronized List<Evidence> analyze(String playerId) {
        List<Evidence> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            if (lastAnalyzeAt != 0 && now - lastAnalyzeAt < ANALYZE_INTERVAL_MS) {
                return out;
            }
            List<Long> snapshot = new ArrayList<>(clicks);
            ClickProfile profile = ClickProfile.fromClickTimestamps(snapshot);
            if (profile.clickCount() < ClickProfile.MIN_CLICKS) {
                return out;
            }
            lastAnalyzeAt = now;
            hasNewData = false;
            behavior.addClickWindow(profile);
            if (profile.robotic()) {
                out.add(Evidence.of(id(), Layer.INPUT, profile.machineLikelihood(),
                        "点击分布机械（连点器特征）: clicks=" + profile.clickCount()
                                + " 方差(s)=" + String.format(Locale.ROOT, "%.4f", profile.intervalVarianceSeconds())
                                + " 熵(bit)=" + String.format(Locale.ROOT, "%.2f", profile.entropyBits())));
            }
            if (profile.gcdPattern()) {
                out.add(Evidence.of(id(), Layer.INPUT, GCD_EVIDENCE_CONFIDENCE,
                        "点击间隔呈固定毫秒倍数模式: gcdRatio="
                                + String.format(Locale.ROOT, "%.2f", profile.gcdPatternRatio())));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "输入分析失败", e);
        }
        return out;
    }
}
