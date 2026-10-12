package com.potatotv.paccclient.detection.scanner;

import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.FeatureVector;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.detection.engine.PdeLocalScanner;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.spi.DetectContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 引擎玩家端六层检测桥（{@link PdeLocalScanner}）测试：
 * 默认关闭不产出；开启后由引擎本地检测器产出证据并写回扩展特征。
 */
class PdeLocalScannerTest {

    private boolean toggleBefore;

    @BeforeEach
    void setUp() {
        toggleBefore = PerfToggles.enabled(PerfToggles.PDE_LOCAL);
    }

    @AfterEach
    void tearDown() {
        PerfToggles.set(PerfToggles.PDE_LOCAL, toggleBefore);
    }

    @Test
    void 默认关闭时不产出且不写扩展特征() {
        PerfToggles.set(PerfToggles.PDE_LOCAL, false);
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(
                new ProcessSnapshot.ProcessInfo(100, "javaw.exe", null, null)));
        FeatureVector fv = new FeatureVector();

        assertTrue(new PdeLocalScanner().detect(new DetectContext(probe, fv)).isEmpty());
        assertEquals(0.0, fv.get("ext_pde_evidence_count"), 1e-9, "关闭时不应产生任何证据字段");
    }

    @Test
    void 开启后空环境不产出() {
        PerfToggles.set(PerfToggles.PDE_LOCAL, true);
        FakeSystemProbe probe = new FakeSystemProbe();
        FeatureVector fv = new FeatureVector();

        assertTrue(new PdeLocalScanner().detect(new DetectContext(probe, fv)).isEmpty());
        assertEquals(0.0, fv.get("ext_pde_evidence_count"), 1e-9);
    }

    @Test
    void 开启后指纹与注入痕迹产出证据() {
        PerfToggles.set(PerfToggles.PDE_LOCAL, true);
        FakeSystemProbe probe = new FakeSystemProbe();
        probe.processes = new ProcessSnapshot(List.of(
                new ProcessSnapshot.ProcessInfo(100, "javaw.exe", null, null),
                new ProcessSnapshot.ProcessInfo(200, "cheatengine-x86_64.exe", null, null)));
        // 释放到 Temp 目录的模块 → 注入痕迹路径异常（L2）
        probe.modules = new ModuleSnapshot("javaw.exe", List.of(
                new ModuleSnapshot.ModuleInfo("evil.dll",
                        "C:\\Users\\x\\AppData\\Local\\Temp\\evil.dll", 0L, 0L)));
        FeatureVector fv = new FeatureVector();

        Optional<DetectionEvent> event = new PdeLocalScanner().detect(new DetectContext(probe, fv));

        assertTrue(fv.get("ext_pde_evidence_count") > 0, "CE 进程 + Temp 模块应产出引擎证据");
        assertTrue(fv.get("ext_pde_risk_score") >= 0);
        assertTrue(event.isPresent(), "多通道证据融合后应产出事件");
        assertTrue(event.get().eventType().startsWith("pde_local_"));
    }
}
