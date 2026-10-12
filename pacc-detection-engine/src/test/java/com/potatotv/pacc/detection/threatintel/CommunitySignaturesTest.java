package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 社区签名上报测试：入队、去重、riskLevel 校验、合并清空、并发安全。
 */
class CommunitySignaturesTest {

    private static CheatFingerprint fingerprint(String id, String name) {
        return new CheatFingerprint(id, FingerprintType.PROCESS, name,
                List.of("community-" + id + ".exe"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.INJECTOR, 3);
    }

    @Test
    void 提交进入待审队列() {
        CommunitySignatures signatures = new CommunitySignatures();
        signatures.submit(fingerprint("sig-1", "社区上报一"));
        assertEquals(1, signatures.pendingCount());
        assertEquals("sig-1", signatures.pending().get(0).id());
    }

    @Test
    void 同id提交去重() {
        CommunitySignatures signatures = new CommunitySignatures();
        signatures.submit(fingerprint("sig-1", "社区上报一"));
        signatures.submit(fingerprint("sig-1", "同id不同名"));
        assertEquals(1, signatures.pendingCount());
    }

    @Test
    void 完全相同特征提交去重() {
        CommunitySignatures signatures = new CommunitySignatures();
        signatures.submit(fingerprint("sig-1", "社区上报一"));
        signatures.submit(new CheatFingerprint("sig-2", FingerprintType.PROCESS, "社区上报一",
                List.of("community-sig-1.exe"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.INJECTOR, 3));
        assertEquals(1, signatures.pendingCount());
    }

    @Test
    void 风险等级越界的指纹无法构造() {
        // record 构造器是 riskLevel 1-5 的第一道闸，submit 的校验在其之上兜底。
        assertThrows(IllegalArgumentException.class, () -> new CheatFingerprint(
                "bad", FingerprintType.PROCESS, "越界下界", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), FingerprintCategory.INJECTOR, 0));
        assertThrows(IllegalArgumentException.class, () -> new CheatFingerprint(
                "bad", FingerprintType.PROCESS, "越界上界", List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), FingerprintCategory.INJECTOR, 6));
    }

    @Test
    void 合并进签名库并清空队列() {
        CommunitySignatures signatures = new CommunitySignatures();
        SignatureLibrary library = SignatureLibrary.of(List.of());
        signatures.submit(fingerprint("sig-1", "社区上报一"));
        signatures.submit(fingerprint("sig-2", "社区上报二"));

        signatures.mergeInto(library);
        assertEquals(2, library.size());
        assertEquals(0, signatures.pendingCount());

        // 空队列重复合并不产生重复注册。
        signatures.mergeInto(library);
        assertEquals(2, library.size());
    }

    @Test
    void 并发提交不丢数据() throws Exception {
        CommunitySignatures signatures = new CommunitySignatures();
        CountDownLatch done = new CountDownLatch(2);
        for (int t = 0; t < 2; t++) {
            final int base = t * 100;
            new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    signatures.submit(fingerprint("sig-" + (base + i), "并发上报" + i));
                }
                done.countDown();
            }).start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(200, signatures.pendingCount());
    }
}
