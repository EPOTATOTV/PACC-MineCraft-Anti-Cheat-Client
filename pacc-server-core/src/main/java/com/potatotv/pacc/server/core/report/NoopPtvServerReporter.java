package com.potatotv.pacc.server.core.report;

import com.potatotv.pacc.server.core.model.DetectionEvent;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerInfo;

/**
 * 什么都不做的上报实现，用于本地运行或单测。
 */
public final class NoopPtvServerReporter implements PtvServerReporter {

    public static final NoopPtvServerReporter INSTANCE = new NoopPtvServerReporter();

    private NoopPtvServerReporter() {
    }

    @Override
    public void register(ServerInfo info) {
        // 空实现
    }

    @Override
    public void report(DetectionEvent event) {
        // 空实现
    }

    @Override
    public void reportPunishment(
            String serverId, String playerUuid, PunishmentType type, String reason, long durationMs) {
        // 空实现
    }
}
