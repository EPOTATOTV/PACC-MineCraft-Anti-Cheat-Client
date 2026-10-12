package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 注入痕迹：路径异常、映像大小为 0、同模块同规则去重。 */
class InjectionTracerTest {

    private LocalScanContext ctx(FakeOsProbe probe) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, SignatureLibrary.of(List.of()), true);
    }

    @Test
    void Temp目录模块出证据且去重() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(new ModuleInfo(
                "dropper.dll", "c:\\users\\u1\\appdata\\local\\temp\\dropper.dll", "", 0x5000, 4096));

        InjectionTracer tracer = new InjectionTracer();
        List<Evidence> first = tracer.scan(ctx(probe));

        assertEquals(1, first.size());
        assertEquals(0.8, first.get(0).confidence());
        assertTrue(first.get(0).detail().contains("dropper.dll"));

        assertTrue(tracer.scan(ctx(probe)).isEmpty(), "同模块同规则只报一次");
    }

    @Test
    void 空路径模块出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(new ModuleInfo("no-path.dll", "", "", 0x6000, 4096));

        List<Evidence> out = new InjectionTracer().scan(ctx(probe));

        assertEquals(1, out.size());
        assertEquals(0.8, out.get(0).confidence());
    }

    @Test
    void 无目录结构的模块路径出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(new ModuleInfo("bare.dll", "bare.dll", "", 0x7000, 4096));

        List<Evidence> out = new InjectionTracer().scan(ctx(probe));

        assertEquals(1, out.size());
        assertEquals(0.8, out.get(0).confidence());
    }

    @Test
    void 映像大小为0出低置信证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(new ModuleInfo(
                "manual-map.dll", "d:\\game\\manual-map.dll", "", 0x8000, 0));

        List<Evidence> out = new InjectionTracer().scan(ctx(probe));

        assertEquals(1, out.size());
        assertEquals(0.5, out.get(0).confidence());
    }

    @Test
    void 正常模块不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(new ModuleInfo(
                "game.dll", "d:\\game\\bin\\game.dll", "", 0x400000, 65536));

        List<Evidence> out = new InjectionTracer().scan(ctx(probe));

        assertTrue(out.isEmpty());
    }
}
