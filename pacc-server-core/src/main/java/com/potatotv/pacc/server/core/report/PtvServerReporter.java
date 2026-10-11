package com.potatotv.pacc.server.core.report;

import com.potatotv.pacc.server.core.model.DetectionEvent;
import com.potatotv.pacc.server.core.model.PunishmentType;
import com.potatotv.pacc.server.core.model.ServerInfo;

/**
 * 检测结果上报通道（对接 PTV 管控后端）。实现方负责序列化与传输。
 */
public interface PtvServerReporter {

    /** 注册当前服务端。 */
    void register(ServerInfo info);

    /** 上报一条检测事件。 */
    void report(DetectionEvent event);

    /** 上报一次实际处置（非告警）。 */
    void reportPunishment(String serverId, String playerUuid, PunishmentType type, String reason, long durationMs);
}
