package com.potatotv.pacc.service.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.potatotv.pacc.cluster.RedisClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** R-09：质询一次性、清单绑定与签名校验。 */
class ModChallengeServiceTest {

    private static final String SECRET = "test-sign-secret";
    private ModChallengeService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ObjectProvider<RedisClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(null);
        service = new ModChallengeService(provider, new ObjectMapper());
        ReflectionTestUtils.setField(service, "ttlMs", 60_000L);
        ReflectionTestUtils.setField(service, "maxElapsedMs", 5_000L);
        ReflectionTestUtils.setField(service, "signSecret", SECRET);
    }

    @Test
    void validSignaturePassesAndChallengeIsConsumed() {
        ModChallengeService.Challenge challenge = service.issue("PT01");
        List<ModChallengeService.ModEntry> mods = List.of(
                new ModChallengeService.ModEntry("alpha", "a".repeat(64)));

        ModChallengeService.Result first = service.verify("PT01", request(challenge, mods, 12L));
        assertEquals("PASS", first.status());

        ModChallengeService.Result replay = service.verify("PT01", request(challenge, mods, 12L));
        assertEquals("FAIL", replay.status());
        assertEquals("no_pending_challenge", replay.reason());
    }

    @Test
    void tamperedModListFailsSignature() {
        ModChallengeService.Challenge challenge = service.issue("PT01");
        List<ModChallengeService.ModEntry> original = List.of(
                new ModChallengeService.ModEntry("alpha", "a".repeat(64)));
        List<ModChallengeService.ModEntry> tampered = List.of(
                new ModChallengeService.ModEntry("alpha", "b".repeat(64)));

        ModChallengeService.Result result = service.verify("PT01", request(challenge, original, 12L, tampered));
        assertEquals("FAIL", result.status());
        assertEquals("signature_invalid", result.reason());
    }

    @Test
    void wrongPteidOrNonceFails() {
        ModChallengeService.Challenge challenge = service.issue("PT01");
        List<ModChallengeService.ModEntry> mods = List.of(
                new ModChallengeService.ModEntry("alpha", "a".repeat(64)));

        ModChallengeService.Result wrongPteid = service.verify("PT99", request(challenge, mods, 12L));
        assertEquals("pteid_mismatch", wrongPteid.reason());

        ModChallengeService.Challenge second = service.issue("PT01");
        String sig = signature(second, mods, 12L);
        ModChallengeService.Result wrongNonce = service.verify("PT01",
                new ModChallengeService.VerifyRequest("s1", "PT01", second.challengeId(),
                        "deadbeefdeadbeefdeadbeefdeadbeef", mods, 12L, sig));
        assertEquals("nonce_mismatch", wrongNonce.reason());
    }

    @Test
    void pluginVerificationMayOmitPteid() {
        ModChallengeService.Challenge challenge = service.issue("PT01");
        List<ModChallengeService.ModEntry> mods = List.of(
                new ModChallengeService.ModEntry("alpha", "a".repeat(64)));

        ModChallengeService.Result result = service.verify(null, request(challenge, mods, 12L));
        assertEquals("PASS", result.status());
    }

    @Test
    void missingSignSecretFailsClosed() {
        ReflectionTestUtils.setField(service, "signSecret", "");
        ModChallengeService.Challenge challenge = service.issue("PT01");
        List<ModChallengeService.ModEntry> mods = List.of(
                new ModChallengeService.ModEntry("alpha", "a".repeat(64)));

        ModChallengeService.Result result = service.verify("PT01", request(challenge, mods, 12L));
        assertEquals("FAIL", result.status());
        assertEquals("sign_secret_missing", result.reason());
    }

    private static ModChallengeService.VerifyRequest request(ModChallengeService.Challenge challenge,
                                                             List<ModChallengeService.ModEntry> signedMods,
                                                             long elapsedMs) {
        return request(challenge, signedMods, elapsedMs, signedMods);
    }

    private static ModChallengeService.VerifyRequest request(ModChallengeService.Challenge challenge,
                                                             List<ModChallengeService.ModEntry> signedMods,
                                                             long elapsedMs,
                                                             List<ModChallengeService.ModEntry> sentMods) {
        return new ModChallengeService.VerifyRequest("s1", "PT01", challenge.challengeId(),
                challenge.nonce(), sentMods, elapsedMs, signature(challenge, signedMods, elapsedMs));
    }

    private static String signature(ModChallengeService.Challenge challenge,
                                    List<ModChallengeService.ModEntry> mods, long elapsedMs) {
        String digest = ModChallengeService.modsDigest(mods);
        String payload = ModChallengeService.canonicalSignaturePayload(
                challenge.challengeId(), challenge.nonce(), digest, elapsedMs);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
