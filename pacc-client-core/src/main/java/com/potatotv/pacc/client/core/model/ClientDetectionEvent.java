package com.potatotv.pacc.client.core.model;

/**
 * 上报 PTV 的载荷。玩家端只上报本机检测结果，不含任何处置字段。
 */
public record ClientDetectionEvent(
        String pteid,
        String playerName,
        String clientVersion,
        ClientCheckType checkType,
        String cheatType,
        int confidence,
        String detail,
        long timestampMs) {
}
