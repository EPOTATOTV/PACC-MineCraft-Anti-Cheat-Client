package com.potatotv.pacc.service;

import java.time.Instant;

/**
 * 跨端证据链中的玩家端硬件指纹（P-06）。
 *
 * <p>玩家端把 WMI / USB 枚举 / 进程列表等维度压成摘要后上报，PTV 只保存摘要。
 * 这里返回摘要与出现次数，不还原任何原始设备明细。
 *
 * @param fingerprintHash 硬件指纹摘要（十六进制）
 * @param firstSeenAt     首次出现时间
 * @param lastSeenAt      最近出现时间
 * @param seenCount       累计出现次数
 */
public record CrossEvidenceHardware(
        String fingerprintHash,
        Instant firstSeenAt,
        Instant lastSeenAt,
        long seenCount) {
}
