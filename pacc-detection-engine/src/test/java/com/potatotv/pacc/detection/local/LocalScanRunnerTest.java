package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 调度器：异常隔离与默认检测器组完整性。 */
class LocalScanRunnerTest {

    private LocalScanContext ctx() {
        return new LocalScanContext(
                "player-1", 1234, "d:\\game", new FakeOsProbe(), SignatureLibrary.of(List.of()), true);
    }

    @Test
    void 单个检测器异常不影响其余检测器() {
        LocalDetector broken = new LocalDetector() {
            @Override
            public String id() {
                return "broken-detector";
            }

            @Override
            public Layer layer() {
                return Layer.PROCESS;
            }

            @Override
            public List<Evidence> scan(LocalScanContext scanCtx) {
                throw new IllegalStateException("boom");
            }
        };
        LocalDetector healthy = new LocalDetector() {
            @Override
            public String id() {
                return "healthy-detector";
            }

            @Override
            public Layer layer() {
                return Layer.PROCESS;
            }

            @Override
            public List<Evidence> scan(LocalScanContext scanCtx) {
                return List.of(Evidence.of("healthy-detector", Layer.PROCESS, 0.5, "ok"));
            }
        };

        LocalScanRunner runner = new LocalScanRunner(List.of(broken, healthy));
        List<Evidence> out = runner.runAll(ctx());

        assertEquals(1, out.size());
        assertEquals("healthy-detector", out.get(0).detectorId());
    }

    @Test
    void 默认检测器组完整且id不重复() {
        List<LocalDetector> detectors = LocalScanRunner.defaultDetectors();

        assertEquals(8, detectors.size());
        long distinct = detectors.stream().map(LocalDetector::id).distinct().count();
        assertEquals(detectors.size(), distinct);
    }

    @Test
    void 默认检测器组可整体运行不外抛() {
        LocalScanRunner runner = new LocalScanRunner(LocalScanRunner.defaultDetectors());

        List<Evidence> out = runner.runAll(ctx());

        assertTrue(out.isEmpty(), "空探针不应产出证据");
    }
}
