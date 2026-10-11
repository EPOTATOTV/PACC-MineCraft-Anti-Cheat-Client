package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Addon 检查：命中作弊 UUID（CHEAT_ADDON）、命中改动物理/伤害的组件（CHEAT_ADDON）、
 * 命中作弊脚本关键词（CHEAT_ADDON_SCRIPT）、X-Ray 资源包（CHEAT_ADDON_XRAY）。
 */
public final class AddonCheck implements ServerCheck {

    /** 默认视为可疑的组件：改动物理或伤害判定，常被作弊附加包使用。 */
    private static final Set<String> DEFAULT_CHEAT_COMPONENTS = Set.of(
            "minecraft:physics",
            "minecraft:damage_sensor",
            "minecraft:knockback_resistance");

    private static final String XRAY_KEYWORD = "xray";

    private final ServerPluginConfig config;

    public AddonCheck(ServerPluginConfig config) {
        this.config = config;
    }

    @Override
    public CheckType type() {
        return CheckType.ADDON;
    }

    @Override
    public List<DetectionResult> onAddons(String playerId, List<AddonInfo> addons) {
        if (addons == null || addons.isEmpty()) {
            return List.of();
        }
        List<DetectionResult> results = new ArrayList<>(2);
        for (AddonInfo addon : addons) {
            if (addon == null) {
                continue;
            }
            String label = addon.name() != null ? addon.name() : String.valueOf(addon.uuid());

            if (addon.uuid() != null && config.cheatAddonUuids().contains(addon.uuid())) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON", 90,
                        "Addon UUID 命中作弊名单: " + addon.uuid(), System.currentTimeMillis()));
            }

            Set<String> components = addon.components();
            if (components != null && components.stream().anyMatch(DEFAULT_CHEAT_COMPONENTS::contains)) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON", 70,
                        "Addon 含可疑组件: " + label, System.currentTimeMillis()));
            }

            if (containsCheatKeyword(addon.scriptKeywords())) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON_SCRIPT", 85,
                        "Addon 脚本关键词命中作弊名单: " + label, System.currentTimeMillis()));
            }

            if (containsXray(addon)) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON_XRAY", 80,
                        "Addon 疑似 X-Ray 资源包: " + label, System.currentTimeMillis()));
            }
        }
        return results;
    }

    private boolean containsCheatKeyword(Set<String> keywords) {
        if (keywords == null) {
            return false;
        }
        Set<String> cheat = config.cheatAddonKeywords();
        return keywords.stream()
                .filter(k -> k != null)
                .map(k -> k.toLowerCase(Locale.ROOT))
                .anyMatch(cheat::contains);
    }

    private static boolean containsXray(AddonInfo addon) {
        if (addon.name() != null && addon.name().toLowerCase(Locale.ROOT).contains(XRAY_KEYWORD)) {
            return true;
        }
        Set<String> keywords = addon.scriptKeywords();
        return keywords != null && keywords.stream()
                .filter(k -> k != null)
                .anyMatch(k -> k.toLowerCase(Locale.ROOT).contains(XRAY_KEYWORD));
    }
}
