package com.potatotv.pacc.detection.server.behavior;

import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.MovementProfile;

/**
 * 移动速度统计离群（技术文档 §7.4：3σ 离群检测）。
 *
 * <p>单维度异常不直接判违规，只提置信 0.5 供融合层合并——
 * 疾跑跳台阶、冰面、药水都会造成正常离群，交给多维度相关性定案。
 */
public final class StatOutlier {

    public static final String DETECTOR_ID = "behavior-outlier";

    /** 离群证据固定置信（文档 §7.4：单维度只提置信）。 */
    private static final double CONFIDENCE = 0.5;

    private final ConcurrentHashMap<String, MovementProfile> profiles = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> lastSpeeds = new ConcurrentHashMap<>();

    /** 记录一次水平速度采样（格/tick）。 */
    public void record(String playerId, double horizontalSpeed) {
        profiles.computeIfAbsent(playerId, k -> new MovementProfile()).addSpeed(horizontalSpeed);
        lastSpeeds.put(playerId, horizontalSpeed);
    }

    /** 检查最近一次采样是否超出玩家基线 3σ。 */
    public Optional<Evidence> check(String playerId) {
        MovementProfile profile = profiles.get(playerId);
        Double lastSpeed = lastSpeeds.get(playerId);
        if (profile == null || lastSpeed == null) {
            return Optional.empty();
        }
        double z = profile.zScore(lastSpeed);
        if (z <= MovementProfile.SIGMA_THRESHOLD) {
            return Optional.empty();
        }
        String detail = String.format(Locale.ROOT,
                "3σ 离群 speed=%.3f z=%.2f 基线均值=%.3f 样本=%d",
                lastSpeed, z, profile.mean(), profile.sampleCount());
        return Optional.of(Evidence.of(DETECTOR_ID, Layer.BEHAVIOR, CONFIDENCE, detail));
    }

    /** 清空玩家画像（下线时调用）。 */
    public void reset(String playerId) {
        profiles.remove(playerId);
        lastSpeeds.remove(playerId);
    }
}
