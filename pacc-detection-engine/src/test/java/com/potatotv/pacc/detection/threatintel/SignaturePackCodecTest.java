package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SignaturePackCodecTest {

    @Test
    void roundTripPreservesEveryField() {
        CheatFingerprint original = sample("wurst-client", "Wurst", "wurst.jar");
        String text = SignaturePackCodec.encode("2026-10-17", List.of(original));
        SignaturePack pack = SignaturePackCodec.decode(text);

        assertEquals("2026-10-17", pack.version());
        assertEquals(1, pack.size());
        CheatFingerprint decoded = pack.fingerprints().get(0);
        assertEquals(original, decoded);
        assertFalse(pack.checksum().isBlank());
    }

    @Test
    void tamperedBodyIsRejected() {
        String text = SignaturePackCodec.encode("v1", List.of(sample("a", "A", "a.jar")));
        // 把风险等级从 4 改成 5：字段合法、格式合法，但校验和必须对不上
        String tampered = text.replace("\t4\t", "\t5\t");
        assertTrue(tampered.contains("\t5\t"), "测试前提：确实改到了正文");
        assertThrows(IllegalArgumentException.class, () -> SignaturePackCodec.decode(tampered));
    }

    @Test
    void missingChecksumIsRejected() {
        String text = SignaturePackCodec.encode("v1", List.of(sample("a", "A", "a.jar")));
        String withoutChecksum = text.substring(0, text.lastIndexOf("checksum="));
        assertThrows(IllegalArgumentException.class, () -> SignaturePackCodec.decode(withoutChecksum));
    }

    @Test
    void unknownFormatIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> SignaturePackCodec.decode("some-other-format\nversion=v1\nchecksum=00\n"));
        assertThrows(IllegalArgumentException.class, () -> SignaturePackCodec.decode(""));
    }

    @Test
    void reservedSeparatorsAreRefusedAtEncodeTime() {
        CheatFingerprint bad = new CheatFingerprint("id", FingerprintType.PROCESS, "a|b",
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.CLIENT, 3);
        assertThrows(IllegalArgumentException.class,
                () -> SignaturePackCodec.encode("v1", List.of(bad)));
    }

    @Test
    void applyReplacesLibraryAndRollbackRestores() {
        SignatureLibrary library = SignatureLibrary.of(List.of(sample("old", "Old", "old.jar")));
        SignaturePack pack = SignaturePackCodec.decode(
                SignaturePackCodec.encode("v2", List.of(sample("new", "New", "new.jar"))));

        List<CheatFingerprint> previous = SignaturePackCodec.apply(pack, library);
        assertEquals(1, library.size());
        assertEquals("new", library.fingerprints().get(0).id());

        SignaturePackCodec.rollback(previous, library);
        assertEquals(1, library.size());
        assertEquals("old", library.fingerprints().get(0).id());
    }

    @Test
    void catalogExposesPackMetadataAndDuplicateCheck() {
        SignaturePack pack = SignaturePackCodec.decode(
                SignaturePackCodec.encode("v3", List.of(sample("x", "X", "x.jar"))));
        CheatCatalog catalog = new CheatCatalog(SignatureLibrary.of(pack.fingerprints()), pack);
        assertEquals("v3", catalog.packVersion());
        assertEquals(pack.checksum(), catalog.packChecksum());
        assertFalse(catalog.hasDuplicateIds());
        assertEquals(List.of("x"), catalog.fingerprintIds());
    }

    @Test
    void duplicateIdsAreDetectable() {
        CheatCatalog catalog = new CheatCatalog(SignatureLibrary.of(List.of(
                sample("dup", "A", "a.jar"), sample("dup", "B", "b.jar"))));
        assertTrue(catalog.hasDuplicateIds());
    }

    private static CheatFingerprint sample(String id, String name, String jar) {
        return new CheatFingerprint(
                id,
                FingerprintType.PROCESS,
                name,
                List.of("javaw.exe", name.toLowerCase()),
                List.of("SomeWindowClass"),
                List.of(name + " Client"),
                List.of(jar),
                List.of("0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"),
                List.of("DE AD BE EF"),
                FingerprintCategory.CLIENT,
                4);
    }
}
