package com.potatotv.pacc.detection.server.packet;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

/**
 * 客户端品牌校验（技术文档 §九：品牌与 Mod 列表一致性）。
 *
 * <p>brand 通常是 "MC|Brand"、"vanilla"、"fml,forge" 这类字符串，直接小写子串匹配
 * 已知作弊客户端名单。客户端品牌属于网络面数据，证据层用 {@link Layer#NETWORK}。
 */
public final class BrandValidator {

    public static final String DETECTOR_ID = "packet-brand";

    /** 已知作弊客户端品牌（小写子串匹配）。 */
    private static final List<String> KNOWN_CHEAT_BRANDS = List.of(
            "wurst", "liquidbounce", "meteor", "impact", "aristois", "vape", "ghost");

    /** 品牌缺失固定置信。 */
    private static final double MISSING_CONFIDENCE = 0.3;

    /** 已知作弊品牌固定置信（硬证据）。 */
    private static final double CHEAT_BRAND_CONFIDENCE = 0.95;

    /** 品牌与 Mod 列表不一致固定置信。 */
    private static final double MISMATCH_CONFIDENCE = 0.5;

    /**
     * 校验客户端品牌。优先级：品牌缺失 → 已知作弊品牌 → 声称 vanilla 但装了 Mod。
     */
    public Optional<Evidence> check(String playerId, String brand, List<String> installedMods) {
        if (brand == null || brand.isBlank()) {
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, MISSING_CONFIDENCE,
                    "客户端品牌缺失 brand=" + (brand == null ? "null" : "<blank>") + " 玩家=" + playerId));
        }
        String lower = brand.toLowerCase(Locale.ROOT);
        for (String cheat : KNOWN_CHEAT_BRANDS) {
            if (lower.contains(cheat)) {
                return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, CHEAT_BRAND_CONFIDENCE,
                        "已知作弊客户端品牌 玩家=" + playerId + " brand=" + brand));
            }
        }
        boolean claimsVanilla = lower.contains("vanilla");
        boolean hasMods = installedMods != null && !installedMods.isEmpty();
        if (claimsVanilla && hasMods) {
            return Optional.of(Evidence.of(DETECTOR_ID, Layer.NETWORK, MISMATCH_CONFIDENCE,
                    String.format(Locale.ROOT, "品牌与 Mod 列表不一致 brand=%s mods=%d",
                            brand, installedMods.size())));
        }
        return Optional.empty();
    }
}
