package com.potatotv.paccclient.detection.scanner;

import com.potatotv.pacc.detection.signature.FingerprintMatch;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 引擎指纹库检测器（技术文档 §6.1 / §5.1 玩家端检测矩阵第一行）。
 *
 * <p>把自研检测引擎（PDE）的作弊指纹库接进端侧 SPI：进程名 + 窗口标题双通道匹配
 * 内置指纹（Wurst / LiquidBounce / CE / Vape 等），哈希与模块通道由
 * {@link DllSignatureScanner} / {@link ModuleScanner} 继续承担，这里不重复。
 *
 * <p>多指标加权原则（SPI 契约）：风险等级 ≥4 的指纹单通道命中即可上报；
 * 低风险指纹需要进程与窗口两个通道同时命中，避免单一线索直接触发处置。
 * 证据分写回 {@code ext_engine_fp_score}，供 PRL 规则与分层融合读取。
 */
public final class EngineFingerprintDetector implements Detector {

    /** 低风险指纹要求双通道印证；高风险（≥4）单通道即可。 */
    static final int SINGLE_CHANNEL_MIN_RISK = 4;

    private static final SignatureLibrary LIBRARY = SignatureLibrary.builtin();

    @Override
    public String id() {
        return "engine-fingerprint";
    }

    @Override
    public long intervalMs() {
        return 30_000L;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        List<String> processNames = new ArrayList<>();
        try {
            ctx.systemProbe().snapshotProcesses().processes()
                    .forEach(p -> processNames.add(p.name()));
        } catch (RuntimeException ex) {
            // 探针不可用 → 本周期不产出（Detector 契约：不抛异常）
            return Optional.empty();
        }

        List<FingerprintMatch> processHits = new ArrayList<>();
        for (String name : processNames) {
            for (FingerprintMatch hit : LIBRARY.matchProcess(name)) {
                if (hit.fingerprint().riskLevel() >= 3) {
                    processHits.add(hit);
                }
            }
        }

        List<FingerprintMatch> windowHits = new ArrayList<>();
        try {
            for (var window : ctx.systemProbe().enumerateWindows()) {
                for (FingerprintMatch hit : LIBRARY.matchWindow("", window.title())) {
                    if (hit.fingerprint().riskLevel() >= 3) {
                        windowHits.add(hit);
                    }
                }
            }
        } catch (RuntimeException ex) {
            // 窗口枚举失败不影响进程通道结论
        }

        Optional<ScoredHit> scored = evaluate(processHits, windowHits);
        if (scored.isEmpty()) {
            return Optional.empty();
        }
        ScoredHit hit = scored.get();
        ctx.putExtended("ext_engine_fp_score", hit.confidence());
        return Optional.of(new DetectionEvent(
                "engine_fingerprint",
                severity(hit.riskLevel()),
                (int) Math.round(hit.confidence() * 100),
                hit.subject(),
                null,
                hit.matchedValue(),
                null,
                null));
    }

    /**
     * 命中裁决：进程/窗口双通道证据合并，按风险等级决定单/双通道门槛。
     * 包内可见便于单元测试。
     */
    static Optional<ScoredHit> evaluate(List<FingerprintMatch> processHits, List<FingerprintMatch> windowHits) {
        double best = 0;
        int bestRisk = 0;
        String bestValue = "";
        String bestSubject = "";
        int hitCount = 0;
        for (FingerprintMatch hit : processHits) {
            hitCount++;
            if (hit.confidence() > best) {
                best = hit.confidence();
                bestRisk = hit.fingerprint().riskLevel();
                bestValue = hit.matchedValue();
                bestSubject = hit.matchedValue();
            }
        }
        for (FingerprintMatch hit : windowHits) {
            hitCount++;
            if (hit.confidence() > best) {
                best = hit.confidence();
                bestRisk = hit.fingerprint().riskLevel();
                bestValue = hit.matchedValue();
                bestSubject = hit.matchedValue();
            }
        }
        if (best <= 0) {
            return Optional.empty();
        }
        // 多指标加权：低风险指纹必须双通道（进程+窗口）同时命中
        if (bestRisk < SINGLE_CHANNEL_MIN_RISK && hitCount < 2) {
            return Optional.empty();
        }
        return Optional.of(new ScoredHit(best, bestRisk, bestValue, bestSubject));
    }

    private static String severity(int riskLevel) {
        return switch (riskLevel) {
            case 5 -> "critical";
            case 4 -> "high";
            default -> "medium";
        };
    }

    /** 裁决结果（测试可见）。 */
    record ScoredHit(double confidence, int riskLevel, String matchedValue, String subject) {
        ScoredHit {
            matchedValue = matchedValue == null ? "" : matchedValue.toLowerCase(Locale.ROOT);
        }
    }
}
