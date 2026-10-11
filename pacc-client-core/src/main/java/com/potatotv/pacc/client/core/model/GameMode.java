package com.potatotv.pacc.client.core.model;

/**
 * 玩家当前游戏模式。检测器据此跳过创造 / 旁观模式，避免把正常飞行当成作弊。
 */
public enum GameMode {
    SURVIVAL,
    CREATIVE,
    ADVENTURE,
    SPECTATOR
}
