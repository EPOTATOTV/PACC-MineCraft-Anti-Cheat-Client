package com.potatotv.pacc.server.core.check;

import com.potatotv.pacc.addon.AddonSignatureStore;
import com.potatotv.pacc.server.core.model.AddonInfo;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Addon 检查：命中作弊 UUID（CHEAT_ADDON）、命中 manifest SHA-256 特征（CHEAT_ADDON）、
 * 命中改动物理/伤害的组件（CHEAT_ADDON）、命中作弊脚本关键词（CHEAT_ADDON_SCRIPT）、
 * X-Ray 资源包（CHEAT_ADDON_XRAY）、全亮资源包（CHEAT_ADDON_FULLBRIGHT）。
 *
 * <p>哈希特征与组件口径复用独立的 {@code pacc-addon-scanner} 模块（{@link AddonSignatureStore}），
 * 玩家端扫描出的 {@link AddonInfo} 可直接交给本检查项。</p>
 */
public final class AddonCheck implements ServerCheck {

    /**
     * 视为可疑的行为组件：改动移动、攻击、物理或伤害判定，常被作弊附加包使用。
     *
     * <p>口径与 {@code pacc-addon-scanner} 里 {@code AddonScanner} 的私有可疑组件集合保持一致，
     * 修改这里时要同步修改那边（那边是私有的，没有互相引用）。</p>
     */
    public static final Set<String> CHEAT_COMPONENTS = Set.of(
            "minecraft:movement",
            "minecraft:attack",
            "minecraft:physics",
            "minecraft:damage_sensor",
            "minecraft:knockback_resistance");

    /** 资源包里的全亮标记：X-Ray 的伴生特征，单独不足以定案，只给低置信度。 */
    public static final String FULLBRIGHT_MARKER = "fullbright";

    private static final String XRAY_KEYWORD = "xray";

    private final ServerPluginConfig config;
    private final AddonSignatureStore signatures;

    public AddonCheck(ServerPluginConfig config) {
        this(config, AddonSignatureStore.empty());
    }

    public AddonCheck(ServerPluginConfig config, AddonSignatureStore signatures) {
        this.config = config;
        this.signatures = signatures == null ? AddonSignatureStore.empty() : signatures;
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
        List<DetectionResult> results = new ArrayList<>(4);
        for (AddonInfo addon : addons) {
            if (addon == null) {
                continue;
            }
            String label = addon.name() != null ? addon.name() : String.valueOf(addon.uuid());

            if (addon.uuid() != null && config.cheatAddonUuids().contains(addon.uuid())) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON", 90,
                        "Addon UUID 命中作弊名单: " + addon.uuid(), System.currentTimeMillis()));
            }

            // 哈希命中比 UUID/名字更可靠：改清单的 name/uuid 无法绕过。
            if (signatures.isCheatSha256(addon.sha256())) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON", 95,
                        "Addon SHA-256 哈希命中作弊名单（改名无法绕过）: " + addon.sha256(),
                        System.currentTimeMillis()));
            }

            Set<String> components = addon.components();
            if (components != null && components.stream().anyMatch(CHEAT_COMPONENTS::contains)) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON", 70,
                        "Addon 含可疑组件: " + label, System.currentTimeMillis()));
            }

            if (components != null && components.contains(FULLBRIGHT_MARKER)) {
                results.add(DetectionResult.violation(CheckType.ADDON, "CHEAT_ADDON_FULLBRIGHT", 50,
                        "Addon 资源包含全亮标记，疑似 X-Ray 伴生特征: " + label, System.currentTimeMillis()));
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
