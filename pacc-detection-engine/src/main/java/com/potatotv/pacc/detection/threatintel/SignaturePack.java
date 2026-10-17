package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;

import java.util.List;

/**
 * 版本化的指纹包（技术文档 §5.5 U-09）。
 *
 * <p>指纹库要能被更新、也要能被撤回，所以每次下发的是一整个**包**而不是增量：包带版本号与
 * 内容校验和，升级是「换包」，回滚是「换回上一个包」。这样不会出现「删掉了一半新增条目」
 * 这种中间态。
 *
 * @param version      包版本，如 {@code 2026-10-17}
 * @param checksum     内容校验和（SHA-256 前 8 字节十六进制），用于发现包被改坏或截断
 * @param fingerprints 包内指纹
 */
public record SignaturePack(String version, String checksum, List<CheatFingerprint> fingerprints) {

    public SignaturePack {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("包版本不能为空");
        }
        version = version.trim();
        checksum = checksum == null ? "" : checksum;
        fingerprints = fingerprints == null ? List.of() : List.copyOf(fingerprints);
    }

    /** 指纹条数。 */
    public int size() {
        return fingerprints.size();
    }
}
