package com.potatotv.pacc.client.core.model;

/**
 * 单个客户端检查项的输出。{@code confidence} 落在 [0,100]，0 表示干净（无违规）。
 */
public record ClientDetectionResult(
        ClientCheckType checkType,
        String cheatType,
        int confidence,
        String detail,
        long timestampMs) {

    public ClientDetectionResult {
        if (confidence < 0 || confidence > 100) {
            throw new IllegalArgumentException("confidence 必须在 [0,100] 内: " + confidence);
        }
    }

    /** 是否构成违规（置信度大于 0）。 */
    public boolean violation() {
        return confidence > 0;
    }

    /** 干净结果。 */
    public static ClientDetectionResult clean(ClientCheckType checkType) {
        return new ClientDetectionResult(checkType, "", 0, "", 0L);
    }

    /** 违规结果。 */
    public static ClientDetectionResult violation(
            ClientCheckType checkType, String cheatType, int confidence, String detail, long timestampMs) {
        return new ClientDetectionResult(checkType, cheatType, confidence, detail, timestampMs);
    }
}
