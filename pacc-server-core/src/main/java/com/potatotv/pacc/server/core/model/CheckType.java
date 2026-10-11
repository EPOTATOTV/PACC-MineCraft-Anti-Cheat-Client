package com.potatotv.pacc.server.core.model;

/**
 * 检查项分类。与上报的 {@code ServerDetectionEvent} 一一对应。
 */
public enum CheckType {
    MOVE,
    COMBAT,
    PACKET,
    INVENTORY,
    INTERACTION,
    CLIENT_BRAND,
    ADDON,
    DATA_CONSISTENCY
}
