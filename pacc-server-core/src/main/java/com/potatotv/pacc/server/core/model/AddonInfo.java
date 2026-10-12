package com.potatotv.pacc.server.core.model;

import com.potatotv.pacc.addon.ScannedAddon;

import java.util.Set;

/**
 * 已安装 Addon / 行为包描述符。可能来自玩家端 Addon 扫描，也可能来自服务端资源包清单。
 */
public record AddonInfo(
        String uuid,
        String name,
        String version,
        String sha256,
        Set<String> scriptKeywords,
        Set<String> components) {

    /**
     * 把玩家端 {@code pacc-addon-scanner} 的扫描结果映射为检测引擎可用的 {@link AddonInfo}，
     * 让 {@code DetectionEngine.handleAddons(...)} 能直接消费扫描输出，无需手写转换。
     * 字段一一对应，{@code sha256} 可能为 null（清单哈希计算失败时）。
     */
    public static AddonInfo from(ScannedAddon addon) {
        if (addon == null) {
            return null;
        }
        return new AddonInfo(addon.uuid(), addon.name(), addon.version(), addon.sha256(),
                addon.scriptKeywords(), addon.components());
    }
}
