package com.potatotv.pacc.addon;

import java.util.Locale;

/** 运行平台识别。用于解析基岩版 com.mojang 默认根目录。 */
public enum Platform {
    WINDOWS,
    ANDROID,
    OTHER;

    /** 按系统属性判断当前平台。 */
    public static Platform detect() {
        String os = lower(System.getProperty("os.name"));
        String vendor = lower(System.getProperty("java.vendor"));
        String vm = lower(System.getProperty("java.vm.name"));
        if (os.contains("android") || vendor.contains("android") || vm.contains("dalvik")) {
            return ANDROID;
        }
        if (os.contains("win")) {
            return WINDOWS;
        }
        return OTHER;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
