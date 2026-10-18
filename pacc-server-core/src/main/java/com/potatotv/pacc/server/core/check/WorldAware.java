package com.potatotv.pacc.server.core.check;

/**
 * 世界方块变更观察口（P-01）。
 *
 * <p>平台把方块放置 / 破坏事件转成材质名后，引擎广播给所有需要世界数据的检查项。
 * 世界副本按世界名共享，玩家通过移动事件绑定到世界；方块变更不按玩家分发。
 */
public interface WorldAware {

    /**
     * 一次方块变更。
     *
     * @param worldName 世界名
     * @param material  方块材质名（平台命名，如 {@code SULFUR_BLOCK}）
     */
    void onBlockChanged(String worldName, int x, int y, int z, String material);
}
