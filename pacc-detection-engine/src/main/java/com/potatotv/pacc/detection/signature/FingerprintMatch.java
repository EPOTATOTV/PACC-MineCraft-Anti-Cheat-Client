package com.potatotv.pacc.detection.signature;

import com.potatotv.pacc.detection.fusion.Evidence;

/**
 * 一次指纹命中（技术文档 §6.1 / §五 检测矩阵）。
 *
 * @param fingerprint  命中的指纹
 * @param channel      实际命中的通道
 * @param matchedValue 命中的具体值（进程名 / 窗口类 / 哈希 / 模式）
 * @param confidence   该通道的置信度 0-1
 */
public record FingerprintMatch(CheatFingerprint fingerprint, FingerprintType channel, String matchedValue, double confidence) {

    public FingerprintMatch {
        if (fingerprint == null) {
            throw new IllegalArgumentException("fingerprint 不能为空");
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 [0,1]");
        }
        // matchedValue 来自进程名 / 窗口标题 / 文件名等 OS 侧数据，剥离控制字符防日志注入
        matchedValue = Evidence.sanitize(matchedValue == null ? "" : matchedValue);
    }
}
