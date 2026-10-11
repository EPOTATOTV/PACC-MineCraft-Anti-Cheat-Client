package com.potatotv.pacc.server.core.model;

/**
 * 上报载荷，对应 proto 里的 {@code ServerDetectionEvent}。
 */
public record DetectionEvent(
        String serverId,
        String playerUuid,
        String playerName,
        String pteid,
        CheckType checkType,
        String cheatType,
        int confidence,
        String detail,
        long timestampMs) {
}
