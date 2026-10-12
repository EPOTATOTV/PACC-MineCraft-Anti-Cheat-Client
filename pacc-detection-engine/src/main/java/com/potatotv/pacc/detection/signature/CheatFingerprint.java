package com.potatotv.pacc.detection.signature;

import java.util.List;

/**
 * 作弊指纹库条目（技术文档 §6.1）。
 *
 * @param id             指纹 ID，如 {@code "wurst-client-jar"}
 * @param type           主匹配通道
 * @param name           作弊软件名
 * @param processNames   进程名模式（小写子串）
 * @param windowClasses  窗口类名（如 CE 的 {@code TFormCheatEngine}）
 * @param windowTitles   窗口标题模式
 * @param moduleNames    注入模块名模式（文件名可伪造，配合 fileHashes 使用）
 * @param fileHashes     文件哈希（SHA-256，小写十六进制）
 * @param memoryPatterns 内存特征码（十六进制字节模式，{@code ??} 为通配符）
 * @param category       作弊类别
 * @param riskLevel      风险等级 1-5
 */
public record CheatFingerprint(
        String id,
        FingerprintType type,
        String name,
        List<String> processNames,
        List<String> windowClasses,
        List<String> windowTitles,
        List<String> moduleNames,
        List<String> fileHashes,
        List<String> memoryPatterns,
        FingerprintCategory category,
        int riskLevel) {

    public CheatFingerprint {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("指纹 id 不能为空");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("指纹 name 不能为空");
        }
        if (riskLevel < 1 || riskLevel > 5) {
            throw new IllegalArgumentException("riskLevel 必须在 1-5: " + id);
        }
        processNames = processNames == null ? List.of() : List.copyOf(processNames);
        windowClasses = windowClasses == null ? List.of() : List.copyOf(windowClasses);
        windowTitles = windowTitles == null ? List.of() : List.copyOf(windowTitles);
        moduleNames = moduleNames == null ? List.of() : List.copyOf(moduleNames);
        fileHashes = fileHashes == null ? List.of() : List.copyOf(fileHashes);
        memoryPatterns = memoryPatterns == null ? List.of() : List.copyOf(memoryPatterns);
    }
}
