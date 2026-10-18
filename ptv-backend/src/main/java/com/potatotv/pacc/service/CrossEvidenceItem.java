package com.potatotv.pacc.service;

import java.time.Instant;

/**
 * 跨端证据链中的单条事件（P-06）。
 *
 * <p>{@code source=CLIENT} 来自玩家端本机检测；{@code source=SERVER} 来自服务端插件。
 * 两者只按 PTEID 关联，不读取任何游戏服务器数据库。
 *
 * @param source            CLIENT / SERVER
 * @param eventId           原事件 ID
 * @param pteid             关联 PTEID
 * @param playerName        服务端玩家名（玩家端事件为空）
 * @param playerUuid        服务端玩家 UUID（玩家端事件为空）
 * @param eventType         玩家端 eventType 或服务端 cheatType
 * @param checkType         服务端检查分类（玩家端事件为空）
 * @param severity          low / medium / high / critical
 * @param riskScore         0-100 风险分（玩家端为端侧预评分，服务端为置信度）
 * @param detail            已脱敏的检测说明
 * @param occurredAt        事件发生时间
 * @param alertId           联动红屏告警 ID（可空）
 * @param serverId          来源服务器（玩家端事件为空）
 * @param redscreenTriggered 服务端事件是否联动触发红屏
 */
public record CrossEvidenceItem(
        String source,
        String eventId,
        String pteid,
        String playerName,
        String playerUuid,
        String eventType,
        String checkType,
        String severity,
        int riskScore,
        String detail,
        Instant occurredAt,
        String alertId,
        String serverId,
        boolean redscreenTriggered) {
}
