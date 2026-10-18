package com.potatotv.pacc.service;

import java.time.Instant;

/**
 * 跨端证据链中的设备指纹（P-06）。
 *
 * <p>玩家端上报的硬件指纹摘要落在 {@code t_device}。这里只返回摘要与设备元信息，
 * 不返回原始 USB / 进程明细；原始证据仍在 DetectionEvent 的 process/memory 字段里。
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
