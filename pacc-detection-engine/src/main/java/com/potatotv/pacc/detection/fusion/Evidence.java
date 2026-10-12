package com.potatotv.pacc.detection.fusion;

import java.util.Objects;

/**
 * 单条检测证据：由某个检测器在某一层产出，携带 0-1 归一化置信度。
 *
 * @param detectorId 产出证据的检测器标识，如 {@code "process-scanner"}
 * @param layer      所属纵深层
 * @param confidence 置信度，取值 [0,1]；0.95 表示 95% 置信
 * @param detail     人类可读的触发说明（不含敏感数据）
 * @param timestampMs 证据产生时间（epoch 毫秒）
 */
public record Evidence(String detectorId, Layer layer, double confidence, String detail, long timestampMs) {

    /** detail 落库/入日志的长度上限，防止超长外部数据灌进证据链。 */
    private static final int MAX_DETAIL_CHARS = 4096;

    public Evidence {
        Objects.requireNonNull(detectorId, "detectorId");
        Objects.requireNonNull(layer, "layer");
        Objects.requireNonNull(detail, "detail");
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence 必须在 [0,1] 内: " + confidence);
        }
        // detail 会进日志与上报通道，而 matchedValue 可能来自进程名 / 文件名等外部数据，
        // 按仓库日志注入防护规范剥离控制字符
        detail = sanitize(detail);
    }

    /**
     * 剥离控制字符（含换行），防止证据内容注入日志流；{@code null} 视为空串，超长截断到
     * {@value #MAX_DETAIL_CHARS} 字符。
     *
     * <p>与 ptv-backend 的 {@code LogSanitizer.clean} 同一字符口径（CR/LF 及其余控制字符一并清掉）。
     * 入参可能直接来自探针读到的进程名 / 窗口标题 / 文件名，null 是现实输入，故这里显式兜底。
     */
    public static String sanitize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(Math.min(s.length(), MAX_DETAIL_CHARS));
        s.chars().limit(MAX_DETAIL_CHARS).forEach(c -> {
            if (c >= 0x20 && c != 0x7F) {
                sb.append((char) c);
            }
        });
        return sb.toString();
    }

    /** 便捷工厂：用当前系统时间戳构造证据。 */
    public static Evidence of(String detectorId, Layer layer, double confidence, String detail) {
        return new Evidence(detectorId, layer, confidence, detail, System.currentTimeMillis());
    }

    /** 0-100 整数分数转 0-1 置信度。 */
    public static double normalize(int score0to100) {
        return Math.max(0.0, Math.min(1.0, score0to100 / 100.0));
    }
}
