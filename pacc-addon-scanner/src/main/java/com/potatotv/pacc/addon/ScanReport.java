package com.potatotv.pacc.addon;

import java.util.List;

/**
 * 一次完整扫描的结果：两个包目录下的所有 Addon，以及各世界的启用绑定。
 *
 * @param addons 扫到的 Addon（含良性包）
 * @param worlds 世界绑定
 */
public record ScanReport(List<ScannedAddon> addons, List<WorldBinding> worlds) {

    /** 只保留被判定为可疑的 Addon。 */
    public List<ScannedAddon> suspiciousAddons() {
        return addons.stream().filter(ScannedAddon::suspicious).toList();
    }
}
