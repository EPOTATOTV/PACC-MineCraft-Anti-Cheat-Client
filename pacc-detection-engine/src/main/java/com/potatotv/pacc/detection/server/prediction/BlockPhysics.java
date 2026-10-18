package com.potatotv.pacc.detection.server.prediction;

import java.util.Map;

/**
 * 方块物理属性表（P-01）：碰撞实体性、滑度与反弹系数。
 *
 * <p>预测引擎过去把地面滑度写死为 0.6，遇到冰面、黏液块、蜂蜜块就会把合法移动算成偏差。
 * 这里把「方块名 → 物理属性」集中成一张表，{@link WorldReplica} 按材质查询，
 * {@link NormalPredictor} 只消费属性，不再认具体方块名。
 *
 * <p>26.2 的硫磺方块 / 硫磺尖刺 / 硫磺矿按整格固体处理；它们的特殊效果（喷泉、尖刺伤害）
 * 不影响水平碰撞与地面摩擦，因此滑度取默认 0.6。若后续官方数据给出不同滑度，只改这张表。
 */
public record BlockPhysics(boolean solid, double slipperiness, double bounciness) {

    /** 默认固体方块。 */
    public static final BlockPhysics SOLID = new BlockPhysics(true, 0.6, 0.0);

    /** 空气 / 可穿过方块。 */
    public static final BlockPhysics AIR = new BlockPhysics(false, 0.6, 0.0);

    private static final Map<String, BlockPhysics> KNOWN = Map.ofEntries(
            Map.entry("ICE", new BlockPhysics(true, 0.98, 0.0)),
            Map.entry("PACKED_ICE", new BlockPhysics(true, 0.98, 0.0)),
            Map.entry("BLUE_ICE", new BlockPhysics(true, 0.98, 0.0)),
            Map.entry("FROSTED_ICE", new BlockPhysics(true, 0.98, 0.0)),
            Map.entry("SLIME_BLOCK", new BlockPhysics(true, 0.8, 0.8)),
            Map.entry("HONEY_BLOCK", new BlockPhysics(true, 0.8, 0.0)),
            Map.entry("SOUL_SAND", new BlockPhysics(true, 0.6, 0.0)),
            Map.entry("POWDER_SNOW", new BlockPhysics(true, 0.6, 0.0)),
            // 26.2 新方块：整格固体，默认滑度
            Map.entry("SULFUR_BLOCK", SOLID),
            Map.entry("SULFUR_SPIKE", SOLID),
            Map.entry("POTENT_SULFUR", SOLID),
            Map.entry("CINNABAR_BLOCK", SOLID),
            Map.entry("SULFUR_ORE", SOLID),
            Map.entry("DEEPSLATE_SULFUR_ORE", SOLID),
            Map.entry("WATER", AIR),
            Map.entry("LAVA", AIR),
            Map.entry("AIR", AIR),
            Map.entry("CAVE_AIR", AIR),
            Map.entry("VOID_AIR", AIR));

    /**
     * 按材质名查询；未知材质按固体处理（保守：宁可少判穿墙，也不把实体方块当空气）。
     * 材质名大小写与命名空间前缀都会被归一化。
     */
    public static BlockPhysics forMaterial(String material) {
        if (material == null || material.isBlank()) {
            return SOLID;
        }
        String key = material.trim().toUpperCase();
        int colon = key.indexOf(':');
        if (colon >= 0) {
            key = key.substring(colon + 1);
        }
        return KNOWN.getOrDefault(key, SOLID);
    }

    /** 该方块是否参与碰撞。 */
    public boolean collides() {
        return solid;
    }
}
