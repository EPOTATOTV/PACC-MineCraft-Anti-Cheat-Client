package com.potatotv.pacc.server.core.model;

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
}
