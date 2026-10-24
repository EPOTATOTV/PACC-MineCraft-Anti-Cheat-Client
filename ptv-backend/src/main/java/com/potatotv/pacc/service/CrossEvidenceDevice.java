package com.potatotv.pacc.service;

import java.time.Instant;

/**
 * 跨端证据链中的登录设备（P-06）。
 *
 * <p>来源是登录时落下的 {@code t_device} 记录，只返回脱敏指纹与设备元信息。
 * 玩家端上报的硬件指纹摘要在 {@link CrossEvidenceHardware} 里单独列出，
 * 原始 USB / 进程明细不经过本接口。
 *
 * @param deviceId          稳定设备标识（pteid + 指纹摘要派生）
 * @param deviceFingerprint 设备指纹摘要（十六进制）
 * @param platform          平台
 * @param deviceName        设备名
 * @param ip               最近登录 IP
 * @param firstLoginAt     首次登录时间
 * @param lastLoginAt      最近登录时间
 * @param active           是否当前设备
 */
public record CrossEvidenceDevice(
        String deviceId,
        String deviceFingerprint,
        String platform,
        String deviceName,
        String ip,
        Instant firstLoginAt,
        Instant lastLoginAt,
        boolean active) {
}
