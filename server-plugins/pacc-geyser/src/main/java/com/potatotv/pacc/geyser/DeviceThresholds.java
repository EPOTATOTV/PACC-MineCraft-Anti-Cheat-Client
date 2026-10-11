package com.potatotv.pacc.geyser;

import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.Collection;
import java.util.Locale;

/**
 * 设备类型识别与阈值基线调整。基岩版各平台的输入方式差异很大（触屏、手柄、键鼠、体感），
 * 用同一套阈值容易误报，所以按设备类型放宽检测基线。
 *
 * <p>纯逻辑，不依赖 Geyser API：调用方把平台名 / 设备系统描述成字符串传进来即可单测。
 */
public final class DeviceThresholds {

    private DeviceThresholds() {
    }

    /** 设备大类。 */
    public enum DeviceClass {
        DESKTOP,
        MOBILE,
        CONSOLE,
        VR,
        UNKNOWN
    }

    /**
     * 阈值调整系数。{@code speedFactor} 作用于水平速度上限，{@code cpsDelta} 是 CPS 上限增量，
     * {@code reachDelta} 是攻击距离上限增量（格），{@code flyTicksFactor} 作用于滞空判定阈值。
     * 系数 ≥ 1 表示更宽松，用于抵消输入方式带来的正常偏差。
     */
    public record Profile(double speedFactor, int cpsDelta, double reachDelta, double flyTicksFactor) {
    }

    /** 按平台标识或设备系统描述判断设备大类。参数可为空，全部为空时返回 {@link DeviceClass#UNKNOWN}。 */
    public static DeviceClass classify(String platformName, String deviceOs) {
        String probe = ((platformName == null ? "" : platformName) + " "
                + (deviceOs == null ? "" : deviceOs)).toLowerCase(Locale.ROOT);
        if (probe.isBlank()) {
            return DeviceClass.UNKNOWN;
        }
        if (containsAny(probe, "vr", "gearvr", "hololens", "oculus", "quest", "pico")) {
            return DeviceClass.VR;
        }
        if (containsAny(probe, "xbox", "ps4", "ps5", "playstation", "switch", "nx", "tvos",
                "appletv", "console")) {
            return DeviceClass.CONSOLE;
        }
        if (containsAny(probe, "android", "ios", "mobile", "phone", "ipad", "iphone",
                "amazon", "kindle", "google")) {
            return DeviceClass.MOBILE;
        }
        if (containsAny(probe, "win32", "windows", "osx", "mac", "linux", "uwp", "desktop", "dedicated")) {
            return DeviceClass.DESKTOP;
        }
        return DeviceClass.UNKNOWN;
    }

    public static Profile profileFor(DeviceClass deviceClass) {
        if (deviceClass == null) {
            return profileFor(DeviceClass.UNKNOWN);
        }
        return switch (deviceClass) {
            case MOBILE -> new Profile(1.25, 2, 0.2, 1.15);
            case CONSOLE -> new Profile(1.10, 0, 0.1, 1.05);
            case VR -> new Profile(1.15, 0, 0.25, 1.10);
            case DESKTOP, UNKNOWN -> new Profile(1.0, 0, 0.0, 1.0);
        };
    }

    /** 取若干个在线设备里最宽松的一档，用于驱动单一引擎配置。空集合按 {@link DeviceClass#UNKNOWN}。 */
    public static DeviceClass mostLenient(Collection<DeviceClass> classes) {
        if (classes == null || classes.isEmpty()) {
            return DeviceClass.UNKNOWN;
        }
        DeviceClass best = DeviceClass.UNKNOWN;
        for (DeviceClass candidate : classes) {
            if (candidate != null && profileFor(candidate).speedFactor() > profileFor(best).speedFactor()) {
                best = candidate;
            }
        }
        return best;
    }

    /** 按设备类型调整阈值，其余配置项原样保留。 */
    public static ServerPluginConfig adjust(ServerPluginConfig base, DeviceClass deviceClass) {
        Profile profile = profileFor(deviceClass);
        return ServerPluginConfig.builder()
                .punishmentMode(base.punishmentMode())
                .confidenceThreshold(base.confidenceThreshold())
                .enabledChecks(base.enabledChecks())
                .maxHorizontalSpeedPerTick(base.maxHorizontalSpeedPerTick() * profile.speedFactor())
                .maxVerticalClimbPerTick(base.maxVerticalClimbPerTick())
                .maxReachDistance(base.maxReachDistance() + profile.reachDelta())
                .maxCps(base.maxCps() + profile.cpsDelta())
                .maxPlacePerTick(base.maxPlacePerTick())
                .maxFallDistanceNoDamage(base.maxFallDistanceNoDamage())
                .flyAirborneTicksTrigger((int) Math.round(base.flyAirborneTicksTrigger() * profile.flyTicksFactor()))
                .cheatClientBrands(base.cheatClientBrands())
                .cheatModNames(base.cheatModNames())
                .cheatAddonUuids(base.cheatAddonUuids())
                .cheatAddonKeywords(base.cheatAddonKeywords())
                .build();
    }

    private static boolean containsAny(String probe, String... needles) {
        for (String needle : needles) {
            if (probe.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
