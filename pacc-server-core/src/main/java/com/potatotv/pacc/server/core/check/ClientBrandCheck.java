package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 客户端标识检查：作弊客户端品牌（CHEAT_CLIENT）与作弊 Mod（CHEAT_MOD）。
 * 品牌与 Mod 名统一转小写后比对，配置里的名单也应为小写。
 */
public final class ClientBrandCheck implements ServerCheck {

    private final ServerPluginConfig config;

    public ClientBrandCheck(ServerPluginConfig config) {
        this.config = config;
    }

    @Override
    public CheckType type() {
        return CheckType.CLIENT_BRAND;
    }

    @Override
    public List<DetectionResult> onClientBrand(String playerId, String brand) {
        if (brand == null) {
            return List.of();
        }
        String normalized = brand.toLowerCase(Locale.ROOT);
        if (config.cheatClientBrands().contains(normalized)) {
            return List.of(DetectionResult.violation(CheckType.CLIENT_BRAND, "CHEAT_CLIENT", 90,
                    "客户端品牌命中作弊名单: " + normalized, System.currentTimeMillis()));
        }
        return List.of();
    }

    @Override
    public List<DetectionResult> onMods(String playerId, List<String> mods) {
        if (mods == null || mods.isEmpty()) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(1);
        for (String mod : mods) {
            if (mod == null) {
                continue;
            }
            String normalized = mod.toLowerCase(Locale.ROOT);
            if (config.cheatModNames().contains(normalized)) {
                results.add(DetectionResult.violation(CheckType.CLIENT_BRAND, "CHEAT_MOD", 85,
                        "Mod 命中作弊名单: " + normalized, System.currentTimeMillis()));
            }
        }
        return results;
    }
}
