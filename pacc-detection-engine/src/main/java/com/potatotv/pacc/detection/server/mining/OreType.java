package com.potatotv.pacc.detection.server.mining;

import java.util.Locale;

/**
 * 矿石种类。用于按种类统计发现率——X-Ray 的特征不是「挖得多」，而是「稀有矿占比异常高」，
 * 不分类就没法算这个比值。
 *
 * <p>只认名字里带 {@code _ORE} 或 {@code ANCIENT_DEBRIS} 的方块；其余方块（石头、泥土、
 * 深板岩等）返回 {@code null}，表示「不是矿石，不进统计」——不能把普通方块算成矿石，
 * 否则分母被稀释，比值失真。
 */
public enum OreType {

    COAL(false),
    IRON(false),
    COPPER(false),
    GOLD(false),
    REDSTONE(false),
    LAPIS(false),
    QUARTZ(false),
    DIAMOND(true),
    EMERALD(true),
    ANCIENT_DEBRIS(true);

    /** 是否属于「稀有矿」——X-Ray 判定用的是稀有矿占比。 */
    private final boolean rare;

    OreType(boolean rare) {
        this.rare = rare;
    }

    public boolean rare() {
        return rare;
    }

    /**
     * 按方块材质名解析矿石种类。
     *
     * @param blockType 平台给的方块名，如 {@code DEEPSLATE_DIAMOND_ORE}
     * @return 矿石种类；不是矿石或名称为空时返回 {@code null}
     */
    public static OreType fromBlock(String blockType) {
        if (blockType == null || blockType.isBlank()) {
            return null;
        }
        String name = blockType.toUpperCase(Locale.ROOT);
        if (name.contains("ANCIENT_DEBRIS")) {
            return ANCIENT_DEBRIS;
        }
        if (!name.contains("_ORE")) {
            return null;
        }
        if (name.contains("DIAMOND_ORE")) {
            return DIAMOND;
        }
        if (name.contains("EMERALD_ORE")) {
            return EMERALD;
        }
        if (name.contains("QUARTZ_ORE")) {
            return QUARTZ;
        }
        if (name.contains("REDSTONE_ORE")) {
            return REDSTONE;
        }
        if (name.contains("LAPIS_ORE")) {
            return LAPIS;
        }
        if (name.contains("GOLD_ORE")) {
            return GOLD;
        }
        if (name.contains("IRON_ORE")) {
            return IRON;
        }
        if (name.contains("COPPER_ORE")) {
            return COPPER;
        }
        if (name.contains("COAL_ORE")) {
            return COAL;
        }
        return null;
    }
}
