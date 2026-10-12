package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块层检测：白名单豁免、未知模块只报一次、指纹命中。
 * 测试中的哈希均为示例值，非真实样本哈希。
 */
class ModuleInspectorTest {

    /** 示例哈希（64 位 hex，非真实样本）。 */
    private static final String SAMPLE_HASH =
            "a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8a1b2c3d4e5f6a7b8";

    private final ModuleInspector inspector = new ModuleInspector();

    private LocalScanContext ctx(FakeOsProbe probe, SignatureLibrary library) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, library, true);
    }

    @Test
    void 白名单系统模块豁免() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("user32.dll", "c:\\windows\\system32\\user32.dll", "", 0x7ff00000, 100),
                new ModuleInfo("KERNEL32.DLL", "c:\\windows\\system32\\kernel32.dll", "", 0x7ff10000, 100));

        List<Evidence> out = inspector.scan(ctx(probe, SignatureLibrary.of(List.of())));

        assertTrue(out.isEmpty());
    }

    @Test
    void 未知模块降置信且只报一次() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("mystery.dll", "d:\\game\\mystery.dll", SAMPLE_HASH, 0x1000, 4096));
        SignatureLibrary library = SignatureLibrary.of(List.of());

        List<Evidence> first = inspector.scan(ctx(probe, library));
        assertEquals(1, first.size());
        assertEquals(0.3, first.get(0).confidence());
        assertEquals(Layer.MODULE, first.get(0).layer());
        assertTrue(first.get(0).detail().contains("未知模块，降置信度"));

        List<Evidence> second = inspector.scan(ctx(probe, library));
        assertTrue(second.isEmpty(), "同模块只报一次");
    }

    @Test
    void 模块名指纹命中出证据() {
        CheatFingerprint evil = new CheatFingerprint("evil-hook", FingerprintType.MODULE, "EvilHook",
                List.of(), List.of(), List.of(), List.of("evilhook.dll"), List.of(), List.of(),
                FingerprintCategory.INJECTOR, 4);
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("evilhook.dll", "d:\\game\\evilhook.dll", "", 0x2000, 4096));

        List<Evidence> out = inspector.scan(ctx(probe, SignatureLibrary.of(List.of(evil))));

        assertEquals(1, out.size());
        assertEquals(0.7, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("evilhook.dll"));
    }

    @Test
    void 哈希命中直接出高置信证据() {
        CheatFingerprint evil = new CheatFingerprint("evil-jar", FingerprintType.MODULE, "EvilJar",
                List.of(), List.of(), List.of(), List.of(), List.of(SAMPLE_HASH), List.of(),
                FingerprintCategory.CLIENT, 5);
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("renamed.dll", "d:\\game\\renamed.dll", SAMPLE_HASH, 0x3000, 4096));

        List<Evidence> out = inspector.scan(ctx(probe, SignatureLibrary.of(List.of(evil))));

        assertEquals(1, out.size());
        assertEquals(1.0, out.get(0).confidence());
    }

    @Test
    void 游戏未运行时不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("mystery.dll", "d:\\game\\mystery.dll", "", 0x1000, 4096));
        LocalScanContext ctx = new LocalScanContext(
                "player-1", 0, "d:\\game", probe, SignatureLibrary.of(List.of()), false);

        assertTrue(inspector.scan(ctx).isEmpty());
    }
}
