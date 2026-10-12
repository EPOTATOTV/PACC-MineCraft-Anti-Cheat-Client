package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ProcessInfo;
import com.potatotv.pacc.detection.local.os.WindowInfo;
import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 进程层检测：CE 进程判定受 gameRunning 门控，窗口类名命中。 */
class ProcessScannerTest {

    private final ProcessScanner scanner = new ProcessScanner();

    private LocalScanContext ctx(FakeOsProbe probe, SignatureLibrary library, boolean gameRunning) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, library, gameRunning);
    }

    @Test
    void 游戏运行时内存工具进程出证据() {
        CheatFingerprint ce = new CheatFingerprint("ce-process", FingerprintType.PROCESS, "Cheat Engine",
                List.of("cheatengine"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.MEMORY_TOOL, 5);
        FakeOsProbe probe = new FakeOsProbe();
        probe.processes = List.of(new ProcessInfo(999, "cheatengine-x86_64.exe", "d:\\tools\\ce.exe"));

        List<Evidence> out = scanner.scan(ctx(probe, SignatureLibrary.of(List.of(ce)), true));

        assertEquals(1, out.size());
        assertEquals(Layer.PROCESS, out.get(0).layer());
        assertEquals("process-scanner", out.get(0).detectorId());
        assertEquals(0.8, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("cheatengine-x86_64.exe"));
        assertTrue(out.get(0).detail().contains("Cheat Engine"));
    }

    @Test
    void 游戏未运行时内存工具进程不出证据() {
        CheatFingerprint ce = new CheatFingerprint("ce-process", FingerprintType.PROCESS, "Cheat Engine",
                List.of("cheatengine"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.MEMORY_TOOL, 5);
        FakeOsProbe probe = new FakeOsProbe();
        probe.processes = List.of(new ProcessInfo(999, "cheatengine.exe", "d:\\tools\\ce.exe"));

        List<Evidence> out = scanner.scan(ctx(probe, SignatureLibrary.of(List.of(ce)), false));

        assertTrue(out.isEmpty());
    }

    @Test
    void 低风险进程指纹不出证据() {
        CheatFingerprint low = new CheatFingerprint("low-risk", FingerprintType.PROCESS, "Suspicious Tool",
                List.of("suspicioustool"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.INJECTOR, 2);
        FakeOsProbe probe = new FakeOsProbe();
        probe.processes = List.of(new ProcessInfo(1000, "suspicioustool.exe", "d:\\tools\\st.exe"));

        List<Evidence> out = scanner.scan(ctx(probe, SignatureLibrary.of(List.of(low)), true));

        assertTrue(out.isEmpty());
    }

    @Test
    void 窗口类名命中出证据() {
        CheatFingerprint ce = new CheatFingerprint("ce-window", FingerprintType.WINDOW, "Cheat Engine",
                List.of(), List.of("TFormCheatEngine"), List.of("cheat engine"), List.of(), List.of(), List.of(),
                FingerprintCategory.MEMORY_TOOL, 5);
        FakeOsProbe probe = new FakeOsProbe();
        probe.windows = List.of(new WindowInfo(999, "Cheat Engine 7.5", "TFormCheatEngine"));

        List<Evidence> out = scanner.scan(ctx(probe, SignatureLibrary.of(List.of(ce)), true));

        assertEquals(1, out.size());
        // 类名命中置信 0.9，优先于标题 0.85
        assertEquals(0.9, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("TFormCheatEngine"));
    }
}
