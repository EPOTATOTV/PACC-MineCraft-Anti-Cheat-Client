package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 客户端标识检查：作弊客户端品牌（CHEAT_CLIENT）、作弊 Mod 名（CHEAT_MOD）与作弊 Mod 哈希（CHEAT_MOD_HASH）。
 * 品牌与 Mod 名统一转小写后比对，配置里的名单也应为小写；哈希比对见 {@link #onModHashes}。
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

    /**
     * Mod 文件哈希比对。哈希与文件名无关，改名无法绕过，故置信度高于纯名字匹配。
     */
    @Override
    public List<DetectionResult> onModHashes(String playerId, List<String> hashes) {
        if (hashes == null || hashes.isEmpty() || config.cheatModHashes().isEmpty()) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(1);
        for (String hash : hashes) {
            if (hash == null) {
                continue;
            }
            String normalized = hash.toLowerCase(Locale.ROOT);
            if (config.cheatModHashes().contains(normalized)) {
                results.add(DetectionResult.violation(CheckType.CLIENT_BRAND, "CHEAT_MOD_HASH", 95,
                        "Mod 哈希命中，改名无法绕过: " + normalized, System.currentTimeMillis()));
            }
        }
        return results;
    }
}
