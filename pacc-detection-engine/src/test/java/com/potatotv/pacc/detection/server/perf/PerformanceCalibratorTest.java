package com.potatotv.pacc.detection.server.perf;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PerformanceCalibratorTest {

    @Test
    void noSamplesFallsBackToNominal() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        assertEquals(PerformanceCalibrator.NOMINAL_TICK_MS, calibrator.effectiveTickMs(), 1e-9);
        assertEquals(20.0, calibrator.tps(), 1e-9);
        assertEquals(1.0, calibrator.reliabilityFactor(), 1e-9);
        assertFalse(calibrator.lowThroughput());
    }

    @Test
    void healthyTicksKeepFullReliability() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        feed(calibrator, 0, 50, 20);
        assertEquals(20.0, calibrator.tps(), 0.2);
        assertEquals(50.0, calibrator.effectiveTickMs(), 0.5);
        assertEquals(1.0, calibrator.reliabilityFactor(), 1e-9);
    }

    @Test
    void lowTpsDownweightsAndReportsMeasuredTick() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        // 10 TPS：每刻 100ms
        feed(calibrator, 0, 100, 20);
        assertEquals(10.0, calibrator.tps(), 0.2);
        assertTrue(calibrator.lowThroughput());
        assertEquals(PerformanceCalibrator.MIN_RELIABILITY, calibrator.reliabilityFactor(), 1e-9);
        // 实测刻长只用于诊断，不参与 Timer 速率计算
        assertEquals(100.0, calibrator.effectiveTickMs(), 1.0);
    }

    @Test
    void thresholdScaleIsNeutralWhenHealthy() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        feed(calibrator, 0, 50, 20);
        assertEquals(1.0, calibrator.thresholdScale(-1), 1e-9);
        assertEquals(1.0, calibrator.thresholdScale(40), 1e-9);
    }

    @Test
    void thresholdScaleRelaxesWithLatencyAndLowTps() {
        PerformanceCalibrator healthy = new PerformanceCalibrator();
        feed(healthy, 0, 50, 20);
        assertEquals(1.0, healthy.thresholdScale(50), 1e-9);
        assertEquals(2.0, healthy.thresholdScale(300), 1e-9);

        PerformanceCalibrator loaded = new PerformanceCalibrator();
        feed(loaded, 0, 100, 20);
        // 低 TPS：在延迟放宽之上再叠半档；延迟拉满时 2.0 + 0.5 = 2.5，仍在总上限内
        assertEquals(1.5, loaded.thresholdScale(50), 1e-9);
        assertEquals(2.5, loaded.thresholdScale(900), 1e-9);
    }

    @Test
    void extremeTickLengthIsClamped() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        feed(calibrator, 0, 300, 12);
        assertEquals(PerformanceCalibrator.MAX_TICK_MS, calibrator.effectiveTickMs(), 1e-9);
    }

    @Test
    void multiSecondGapsAreIgnored() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        feed(calibrator, 0, 50, 10);
        // 断点 / 长 GC 造成的 2 秒间隔不应污染刻长窗口
        calibrator.recordTick(50L * 10 + 2000);
        assertEquals(50.0, calibrator.effectiveTickMs(), 1.0);
    }

    @Test
    void latencyFactorRampsToCap() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        assertEquals(1.0, calibrator.latencyFactor(-1), 1e-9);
        assertEquals(1.0, calibrator.latencyFactor(50), 1e-9);
        assertEquals(1.5, calibrator.latencyFactor(175), 1e-9);
        assertEquals(2.0, calibrator.latencyFactor(300), 1e-9);
        assertEquals(2.0, calibrator.latencyFactor(900), 1e-9);
    }

    @Test
    void describeMentionsTpsAndTickLength() {
        PerformanceCalibrator calibrator = new PerformanceCalibrator();
        feed(calibrator, 0, 50, 8);
        String text = calibrator.describe();
        assertTrue(text.contains("TPS"));
        assertTrue(text.contains("刻长"));
    }

    /** 以 {@code stepMs} 为固定间隔喂 {@code count} 个刻样本。 */
    private static void feed(PerformanceCalibrator calibrator, long startMs, long stepMs, int count) {
        for (int i = 0; i <= count; i++) {
            calibrator.recordTick(startMs + i * stepMs);
        }
    }
}
