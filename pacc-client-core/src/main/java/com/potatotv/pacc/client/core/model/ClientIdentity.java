package com.potatotv.pacc.client.core.model;

/**
 * 本地玩家身份。玩家端上报时用它填充 PTEID 与展示名，随加载器启动时注入。
 */
public record ClientIdentity(String pteid, String playerName, String clientVersion) {

    public ClientIdentity {
        if (pteid == null || playerName == null || clientVersion == null) {
            throw new IllegalArgumentException("ClientIdentity 字段不能为 null");
        }
    }
}
