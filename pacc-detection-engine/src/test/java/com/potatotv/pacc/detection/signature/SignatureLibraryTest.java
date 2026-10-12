package com.potatotv.pacc.detection.signature;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指纹库门面测试：内置库关键命中、各通道置信度、合法程序零误报。
 *
 * <p>通道置信度契约：哈希 1.0 &gt; 内存 0.95 &gt; 窗口类名 0.9 &gt; 进程名 0.8
 * &gt; 模块名 0.7 &gt; 配置 0.6。
 */
class SignatureLibraryTest {

    /** 自造的 64 位十六进制串，只用于验证哈希通道，非任何真实样本哈希。 */
    private static final String TEST_SHA256 =
            "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

    @Test
    void 内置库命中CheatEngine进程() {
        List<FingerprintMatch> hits = SignatureLibrary.builtin().matchProcess("cheatengine-x86_64.exe");
        assertFalse(hits.isEmpty());
        assertTrue(hits.stream().anyMatch(h -> h.fingerprint().id().equals("cheat-engine")));
        assertEquals(0.8, hits.get(0).confidence());
    }

    @Test
    void 内置库命中CheatEngine窗口类() {
        List<FingerprintMatch> hits = SignatureLibrary.builtin().matchWindow("TFormCheatEngine", "");
        assertTrue(hits.stream()
                .anyMatch(h -> h.fingerprint().id().equals("cheat-engine-window") && h.confidence() == 0.9));
    }

    @Test
    void 模块名与哈希通道置信度() {
        SignatureLibrary library = SignatureLibrary.of(List.of());
        CheatFingerprint evil = new CheatFingerprint("test-injector", FingerprintType.MODULE, "测试注入器",
                List.of(), List.of(), List.of(), List.of("evil-inject.dll"), List.of(TEST_SHA256), List.of(),
                FingerprintCategory.INJECTOR, 4);
        library.register(evil);

        List<FingerprintMatch> byName = library.matchModule("evil-inject.dll", null);
        assertEquals(1, byName.size());
        assertEquals(0.7, byName.get(0).confidence());

        // 带路径的模块名按后缀匹配。
        List<FingerprintMatch> bySuffix = library.matchModule("C:\\Temp\\evil-inject.dll", "");
        assertEquals(1, bySuffix.size());
        assertEquals(0.7, bySuffix.get(0).confidence());

        // 哈希命中与文件名无关，且置信度最高。
        List<FingerprintMatch> byHash = library.matchModule("innocent.dll", TEST_SHA256);
        assertEquals(1, byHash.size());
        assertEquals(1.0, byHash.get(0).confidence());
        assertEquals("test-injector", byHash.get(0).fingerprint().id());
    }

    @Test
    void 内存特征码通道() {
        CheatFingerprint fp = new CheatFingerprint("test-memory", FingerprintType.MEMORY, "测试内存特征",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of("DE AD BE EF 00 42 ?? 7F"), FingerprintCategory.MEMORY_TOOL, 3);
        SignatureLibrary library = SignatureLibrary.of(List.of(fp));

        byte[] dump = {(byte) 0x90, (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF,
                0x00, 0x42, (byte) 0xA5, 0x7F, 0x00};
        List<FingerprintMatch> hits = library.matchMemory(dump);
        assertEquals(1, hits.size());
        assertEquals(0.95, hits.get(0).confidence());

        byte[] clean = {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A};
        assertTrue(library.matchMemory(clean).isEmpty());
        assertTrue(library.matchMemory(null).isEmpty());
    }

    @Test
    void 配置标记通道() {
        SignatureLibrary library = SignatureLibrary.builtin();
        List<FingerprintMatch> hits =
                library.matchConfig("{\"profile\": \"liquidbounce\", \"scripts\": []}");
        assertTrue(hits.stream()
                .anyMatch(h -> h.fingerprint().id().equals("liquidbounce-config") && h.confidence() == 0.6));
        assertTrue(library.matchConfig("just a normal mod config").isEmpty());
    }

    @Test
    void 合法程序零命中() {
        SignatureLibrary library = SignatureLibrary.builtin();
        assertTrue(library.matchProcess("notepad.exe").isEmpty());
        assertTrue(library.matchProcess("chrome.exe").isEmpty());
        assertTrue(library.matchProcess("optifine.jar").isEmpty());
        assertTrue(library.matchModule("optifine.jar", "").isEmpty());
        assertTrue(library.matchWindow("Notepad", "Untitled - Notepad").isEmpty());
    }
}
