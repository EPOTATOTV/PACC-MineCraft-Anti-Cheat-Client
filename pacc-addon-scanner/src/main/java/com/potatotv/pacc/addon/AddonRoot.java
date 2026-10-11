package com.potatotv.pacc.addon;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 基岩版 com.mojang 根目录解析。默认根目录按平台推导，也允许调用方自行注入
 * （便于测试与自定义安装路径）。
 */
public final class AddonRoot {

    private static final String PACKAGE_DIR = "Microsoft.MinecraftUWP_8wekyb3d8bbwe";

    private AddonRoot() {
    }

    /** 按当前平台返回默认根目录；未知平台返回 null。 */
    public static Path defaultRoot() {
        return resolve(Platform.detect(), System.getenv("LOCALAPPDATA"));
    }

    /**
     * 指定平台与本地 AppData 路径，解析出默认 com.mojang 根目录。
     *
     * @param platform     目标平台
     * @param localAppData Windows 的 %LocalAppData%；为 null 时回退到 user.home
     * @return 根目录；未知平台返回 null
     */
    public static Path resolve(Platform platform, String localAppData) {
        if (platform == null) {
            return null;
        }
        return switch (platform) {
            case WINDOWS -> windowsRoot(localAppData);
            case ANDROID -> Paths.get("/storage", "emulated", "0", "games", "com.mojang");
            case OTHER -> null;
        };
    }

    private static Path windowsRoot(String localAppData) {
        Path base;
        if (localAppData != null && !localAppData.isBlank()) {
            base = Paths.get(localAppData);
        } else {
            base = Paths.get(System.getProperty("user.home", "."), "AppData", "Local");
        }
        return base.resolve("Packages").resolve(PACKAGE_DIR)
                .resolve("LocalState").resolve("games").resolve("com.mojang");
    }
}
