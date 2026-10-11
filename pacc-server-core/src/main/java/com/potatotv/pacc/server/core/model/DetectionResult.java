package com.potatotv.pacc.server.core.model;

/**
 * 单个检查项的输出。{@code confidence} 落在 [0,100]，0 表示干净（无违规）。
 */
public record DetectionResult(
        CheckType checkType,
        String cheatType,
        int confidence,
        String detail,
        long timestampMs) {

    public DetectionResult {
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence 必须在 [0,100] 内: " + confidence);
        }
    }

    /** 是否构成违规（置信度大于 0）。 */
    public boolean violation() {
        return confidence > 0;
    }

    /** 干净结果。 */
    public static DetectionResult clean(CheckType checkType) {
        return new DetectionResult(checkType, "", 0, "", 0L);
    }

    /** 违规结果。 */
    public static DetectionResult violation(
            CheckType checkType, String cheatType, int confidence, String detail, long timestampMs) {
        return new DetectionResult(checkType, cheatType, confidence, detail, timestampMs);
    }
}
