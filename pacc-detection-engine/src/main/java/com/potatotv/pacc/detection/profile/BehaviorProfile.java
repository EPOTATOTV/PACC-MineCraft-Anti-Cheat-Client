package com.potatotv.pacc.detection.profile;

import java.util.ArrayList;
import java.util.List;

/**
 * 会话行为画像：聚合点击 / 旋转 / 移动三类子画像，并维护会话级行为序列
 * （技术文档 §4.2 profile 包 / §7.4 行为统计）。
 *
 * <p>画像按 30 秒窗口滚动产出（文档 §5.1 输入分析口径），跨窗口聚合为会话基线。
 */
public final class BehaviorProfile {

    private final MovementProfile movement = new MovementProfile();
    private final List<ClickProfile> clickWindows = new ArrayList<>();
    private final List<AimProfile> aimWindows = new ArrayList<>();
    private long startedAtMs;
    private long lastUpdateMs;

    /** 会话开始时间（epoch 毫秒）。 */
    public long startedAtMs() {
        return startedAtMs;
    }

    /** 最近一次更新时间。 */
    public long lastUpdateMs() {
        return lastUpdateMs;
    }

    /** 移动基线（滚动维护，跨窗口）。 */
    public MovementProfile movement() {
        return movement;
    }

    /** 登记一个窗口的点击画像。 */
    public synchronized void addClickWindow(ClickProfile profile) {
        if (profile == null || profile.clickCount() == 0) {
            return;
        }
        clickWindows.add(profile);
        touch();
        if (clickWindows.size() > 120) {
            clickWindows.removeFirst();
        }
    }

    /** 登记一个窗口的旋转画像。 */
    public synchronized void addAimWindow(AimProfile profile) {
        if (profile == null || profile.sampleCount() == 0) {
            return;
        }
        aimWindows.add(profile);
        touch();
        if (aimWindows.size() > 120) {
            aimWindows.removeFirst();
        }
    }

    /** 会话级机械点击占比：机械窗口数 / 有效窗口数。 */
    public synchronized double roboticClickRatio() {
        if (clickWindows.isEmpty()) {
            return 0.0;
        }
        long robotic = clickWindows.stream().filter(ClickProfile::robotic).count();
        return (double) robotic / clickWindows.size();
    }

    /** 会话级最大机器旋转 likelihood。 */
    public synchronized double maxAimMachineLikelihood() {
        return aimWindows.stream().mapToDouble(AimProfile::machineLikelihood).max().orElse(0.0);
    }

    /** 点击窗口画像只读视图。 */
    public synchronized List<ClickProfile> clickWindows() {
        return List.copyOf(clickWindows);
    }

    /** 旋转窗口画像只读视图。 */
    public synchronized List<AimProfile> aimWindows() {
        return List.copyOf(aimWindows);
    }

    private void touch() {
        long now = System.currentTimeMillis();
        if (startedAtMs == 0) {
            startedAtMs = now;
        }
        lastUpdateMs = now;
    }
}
