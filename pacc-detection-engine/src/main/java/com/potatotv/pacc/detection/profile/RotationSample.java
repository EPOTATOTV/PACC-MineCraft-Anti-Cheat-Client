package com.potatotv.pacc.detection.profile;

/** 单个旋转采样：视角角度与时间（技术文档 §7.3 旋转分析输入）。 */
public record RotationSample(float yawDeg, float pitchDeg, long timestampMs, boolean attacking) {

    /** 计算相邻采样的 yaw 角速度（度/秒），时间差为 0 时返回 0。 */
    public static double yawRate(RotationSample a, RotationSample b) {
        double dt = (b.timestampMs() - a.timestampMs()) / 1000.0;
        if (dt <= 0) {
            return 0.0;
        }
        double dy = Math.abs(normalizeDelta(b.yawDeg() - a.yawDeg()));
        return dy / dt;
    }

    /** 计算相邻采样的 pitch 角速度（度/秒）。 */
    public static double pitchRate(RotationSample a, RotationSample b) {
        double dt = (b.timestampMs() - a.timestampMs()) / 1000.0;
        if (dt <= 0) {
            return 0.0;
        }
        return Math.abs(b.pitchDeg() - a.pitchDeg()) / dt;
    }

    /** 把角度差归一化到 [-180, 180)。 */
    public static double normalizeDelta(double delta) {
        double d = delta % 360.0;
        if (d >= 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
