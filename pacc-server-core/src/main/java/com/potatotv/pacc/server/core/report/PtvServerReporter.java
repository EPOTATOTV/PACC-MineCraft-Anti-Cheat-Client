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

    /**
     * 把 Mod 清单质询证明转给 PTV 核验（R-09）。
     *
     * <p>默认实现不做任何事并回 false，表示该上报通道未接质询核验；
     * 支持的服务端实现应异步请求并在拿到结果后回调。</p>
     */
    default void reportModProof(ModProofReport report, java.util.function.Consumer<Boolean> onResult) {
        if (onResult != null) {
            onResult.accept(false);
        }
    }
}
