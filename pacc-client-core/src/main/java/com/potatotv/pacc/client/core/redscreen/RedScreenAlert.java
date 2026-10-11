package com.potatotv.pacc.client.core.redscreen;

/**
 * 一次红屏警告的载荷。{@code eventId} 与 PTV 端留痕记录对应。
 */
public record RedScreenAlert(String eventId, String reason, long durationMs) {
}
