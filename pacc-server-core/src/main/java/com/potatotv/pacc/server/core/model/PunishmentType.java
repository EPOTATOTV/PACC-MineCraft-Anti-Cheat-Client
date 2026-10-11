package com.potatotv.pacc.server.core.model;

/**
 * 处置类型。默认（{@link #ALERT}）只告警；{@link #KICK} 及以上必须由配置显式打开。
 */
public enum PunishmentType {
    ALERT,
    KICK,
    TEMP_BAN,
    BAN
}
