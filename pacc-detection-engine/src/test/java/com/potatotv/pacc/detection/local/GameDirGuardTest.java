package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.FileEntry;
import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 游戏目录检测：作弊资源名、xray 仅提示、合法 Mod 豁免、指纹命中与去重。 */
class GameDirGuardTest {

    private LocalScanContext ctx(FakeOsProbe probe, SignatureLibrary library) {
        return new LocalScanContext("player-1", 1234, "d:\\game", probe, library, true);
    }

    @Test
    void 作弊客户端资源出高置信证据() {
        FakeOsProbe probe = new FakeOsProbe();
        // sha256 为示例占位，非真实样本哈希
        probe.files = List.of(new FileEntry("mods/wurst-client.jar", 1024, "cafe0000cafe0000cafe0000cafe0000"));

        List<Evidence> out = new GameDirGuard().scan(ctx(probe, SignatureLibrary.of(List.of())));

        assertEquals(1, out.size());
        assertEquals(0.8, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("wurst-client.jar"));
    }

    @Test
    void xray资源仅提示不判定() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.files = List.of(new FileEntry("assets/xray-resource-pack.zip", 2048, "beef0000beef0000beef0000beef0000"));

        List<Evidence> out = new GameDirGuard().scan(ctx(probe, SignatureLibrary.of(List.of())));

        assertEquals(1, out.size());
        assertEquals(0.4, out.get(0).confidence());
        assertTrue(out.get(0).detail().contains("仅提示"));
    }

    @Test
    void 合法Mod文件豁免() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.files = List.of(new FileEntry("mods/sodium-0.5.3.jar", 999, "dead0000dead0000dead0000dead0000"));

        List<Evidence> out = new GameDirGuard().scan(ctx(probe, SignatureLibrary.of(List.of())));

        assertTrue(out.isEmpty());
    }

    @Test
    void 指纹模块名命中出证据() {
        CheatFingerprint evil = new CheatFingerprint("evil-mod", FingerprintType.MODULE, "EvilMod",
                List.of(), List.of(), List.of(), List.of("hackmod.jar"), List.of(), List.of(),
                FingerprintCategory.CLIENT, 4);
        FakeOsProbe probe = new FakeOsProbe();
        probe.files = List.of(new FileEntry("mods/hackmod.jar", 512, "f00d0000f00d0000f00d0000f00d0000"));

        List<Evidence> out = new GameDirGuard().scan(ctx(probe, SignatureLibrary.of(List.of(evil))));

        assertEquals(1, out.size());
        assertEquals(0.7, out.get(0).confidence());
    }

    @Test
    void 同文件只报一次() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.files = List.of(new FileEntry("mods/wurst-client.jar", 1024, "cafe0000cafe0000cafe0000cafe0000"));
        SignatureLibrary library = SignatureLibrary.of(List.of());

        GameDirGuard guard = new GameDirGuard();
        assertEquals(1, guard.scan(ctx(probe, library)).size());
        assertTrue(guard.scan(ctx(probe, library)).isEmpty());
    }

    @Test
    void 目录参数为空时不出证据() {
        FakeOsProbe probe = new FakeOsProbe();
        probe.files = List.of(new FileEntry("mods/wurst-client.jar", 1024, "cafe0000cafe0000cafe0000cafe0000"));

        LocalScanContext ctx = new LocalScanContext(
                "player-1", 1234, "", probe, SignatureLibrary.of(List.of()), true);

        assertTrue(new GameDirGuard().scan(ctx).isEmpty());
    }
}
