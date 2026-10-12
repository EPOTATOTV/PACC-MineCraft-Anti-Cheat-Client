package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** API Hook 校验：E9 / FF 25 序言判定、节流与模块缺失短路。 */
class ApiHookCheckerTest {

    private LocalScanContext ctx(FakeOsProbe probe) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, SignatureLibrary.of(List.of()), true);
    }

    @Test
    void E9序言出证据且节流生效() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("user32.dll", "c:\\windows\\system32\\user32.dll", "", 0x7ff00000, 100));
        probe.moduleCode = Map.of("user32.dll", new byte[] {
                (byte) 0xE9, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        ApiHookChecker checker = new ApiHookChecker();
        List<Evidence> first = checker.scan(ctx(probe));

        assertEquals(1, first.size());
        assertEquals(Layer.MODULE, first.get(0).layer());
        assertEquals(0.75, first.get(0).confidence());
        assertTrue(first.get(0).detail().contains("user32.dll"));
        assertTrue(first.get(0).detail().contains("inline hook"));

        List<Evidence> second = checker.scan(ctx(probe));
        assertTrue(second.isEmpty(), "30 秒节流内第二轮应为空");
    }

    @Test
    void FF25序言出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("ws2_32.dll", "c:\\windows\\system32\\ws2_32.dll", "", 0x7ff20000, 100));
        probe.moduleCode = Map.of("ws2_32.dll", new byte[] {
                0x48, (byte) 0x89, (byte) 0xFF, 0x25, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        List<Evidence> out = new ApiHookChecker().scan(ctx(probe));

        assertEquals(1, out.size());
    }

    @Test
    void 干净代码不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("user32.dll", "c:\\windows\\system32\\user32.dll", "", 0x7ff00000, 100));
        probe.moduleCode = Map.of("user32.dll", new byte[] {
                0x48, (byte) 0x89, 0x5C, 0x24, 0x08, 0x48, (byte) 0x89, 0x74,
                0x24, 0x10, 0x57, 0x48, (byte) 0x83, (byte) 0xEC, 0x20, 0x00});

        List<Evidence> out = new ApiHookChecker().scan(ctx(probe));

        assertTrue(out.isEmpty());
    }

    @Test
    void 受检模块不存在时不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("game.exe", "d:\\game\\game.exe", "", 0x400000, 65536));

        List<Evidence> out = new ApiHookChecker().scan(ctx(probe));

        assertTrue(out.isEmpty());
    }
}
