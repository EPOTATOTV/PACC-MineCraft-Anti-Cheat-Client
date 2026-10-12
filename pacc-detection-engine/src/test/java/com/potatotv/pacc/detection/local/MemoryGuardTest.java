package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.HandleInfo;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 内存层检测：句柄审计、主模块内存特征、节流与未运行短路。 */
class MemoryGuardTest {

    private LocalScanContext ctx(FakeOsProbe probe, SignatureLibrary library, boolean gameRunning) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, library, gameRunning);
    }

    @Test
    void 外部进程完全访问句柄出证据且节流生效() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.handles = List.of(new HandleInfo(777, "game-hacker", HandleInfo.PROCESS_ALL_ACCESS));

        MemoryGuard guard = new MemoryGuard();
        List<Evidence> first = guard.scan(ctx(probe, SignatureLibrary.of(List.of()), true));

        assertEquals(1, first.size());
        assertEquals(Layer.MEMORY, first.get(0).layer());
        assertEquals(0.85, first.get(0).confidence());
        assertTrue(first.get(0).detail().contains("game-hacker"));

        // 30 秒节流：紧接着的第二轮直接空
        List<Evidence> second = guard.scan(ctx(probe, SignatureLibrary.of(List.of()), true));
        assertTrue(second.isEmpty());
    }

    @Test
    void 系统来源进程句柄豁免() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.handles = List.of(new HandleInfo(4, "system", HandleInfo.PROCESS_ALL_ACCESS));

        List<Evidence> out = new MemoryGuard().scan(ctx(probe, SignatureLibrary.of(List.of()), true));

        assertTrue(out.isEmpty());
    }

    @Test
    void 非完全访问句柄不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.handles = List.of(new HandleInfo(777, "some-tool", 0x1F0FFF));

        List<Evidence> out = new MemoryGuard().scan(ctx(probe, SignatureLibrary.of(List.of()), true));

        assertTrue(out.isEmpty());
    }

    @Test
    void 游戏未运行时不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.handles = List.of(new HandleInfo(777, "game-hacker", HandleInfo.PROCESS_ALL_ACCESS));

        List<Evidence> out = new MemoryGuard().scan(ctx(probe, SignatureLibrary.of(List.of()), false));

        assertTrue(out.isEmpty());
    }

    @Test
    void 主模块内存特征命中出证据() {
        // 内存特征模式为示例值（PE 头惯例字节组合），非真实作弊样本
        CheatFingerprint mem = new CheatFingerprint("mem-pattern", FingerprintType.MEMORY, "MemoryTool Pattern",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of("DE AD BE EF 00 13 37"),
                FingerprintCategory.MEMORY_TOOL, 5);
        FakeOsProbe probe = new FakeOsProbe();
        probe.modules = List.of(
                new ModuleInfo("other.dll", "d:\\game\\other.dll", "", 0x7ff00000, 100),
                new ModuleInfo("game.exe", "d:\\game\\game.exe", "", 0x400000, 65536));
        probe.moduleCode = Map.of("game.exe", new byte[] {
                (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF, 0x00, 0x13, 0x37, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00});

        List<Evidence> out = new MemoryGuard().scan(
                ctx(probe, SignatureLibrary.of(List.of(mem)), true));

        assertEquals(1, out.size());
        assertEquals(0.95, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("game.exe"));
    }
}
