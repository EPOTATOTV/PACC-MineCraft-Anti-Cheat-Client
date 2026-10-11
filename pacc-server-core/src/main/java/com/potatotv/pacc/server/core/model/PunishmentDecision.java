package com.potatotv.pacc.server.core.model;

/**
 * 处置决定。{@code durationMs} 仅对临时封禁有意义，其余为 0。
 */
public record PunishmentDecision(
        String playerId,
        PunishmentType type,
        String reason,
        long durationMs) {
}
