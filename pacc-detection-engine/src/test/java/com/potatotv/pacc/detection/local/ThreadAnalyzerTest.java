package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ThreadInfo;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 线程注入痕迹：未映射起始地址判定与同线程去重。 */
class ThreadAnalyzerTest {

    private LocalScanContext ctx(FakeOsProbe probe) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, SignatureLibrary.of(List.of()), true);
    }

    @Test
    void 未映射线程出证据且同线程去重() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.threads = List.of(
                new ThreadInfo(42, 0x12340000, "", false),
                new ThreadInfo(43, 0x00401000, "game.exe", true));

        ThreadAnalyzer analyzer = new ThreadAnalyzer();
        List<Evidence> first = analyzer.scan(ctx(probe));

        assertEquals(1, first.size());
        assertEquals(Layer.MODULE, first.get(0).layer());
        assertEquals(0.9, first.get(0).confidence());
        assertTrue(first.get(0).detail().contains("shellcode"));
        assertTrue(first.get(0).detail().contains("未映射区域"));

        List<Evidence> second = analyzer.scan(ctx(probe));
        assertTrue(second.isEmpty(), "同一线程只报一次");
    }

    @Test
    void 起始模块名非空时进detail() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.threads = List.of(new ThreadInfo(44, 0x22330000, "evil.dll", false));

        List<Evidence> out = new ThreadAnalyzer().scan(ctx(probe));

        assertEquals(1, out.size());
        assertTrue(out.get(0).detail().contains("evil.dll"));
    }

    @Test
    void 新线程再报_旧线程不重复() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.threads = List.of(new ThreadInfo(42, 0x12340000, "", false));

        ThreadAnalyzer analyzer = new ThreadAnalyzer();
        analyzer.scan(ctx(probe));

        probe.threads = List.of(
                new ThreadInfo(42, 0x12340000, "", false),
                new ThreadInfo(45, 0x12350000, "", false));

        List<Evidence> third = analyzer.scan(ctx(probe));
        assertEquals(1, third.size());
        assertTrue(third.get(0).detail().contains("thread=45"));
    }
}
