package com.potatotv.pacc.rule;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-09 规则签名：有效签名通过、篡改正文失败、强制模式缺签名拒绝。
 */
class RuleSignatureServiceTest {

    private static final String RULE = "packet_26x";
    private static final String VERSION = "1.0.0";
    private static final String SOURCE = "rule \"packet_26x\" { }";

    private KeyPair keyPair;
    private String publicKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    void validSignaturePasses() throws Exception {
        RuleSignatureService service = new RuleSignatureService(false, publicKeyBase64);
        assertTrue(service.verify(RULE, VERSION, SOURCE, sign(RULE, VERSION, SOURCE)));
        assertTrue(service.keyConfigured());
        assertTrue("SHA256withRSA".equals(service.algorithm()));
    }

    @Test
    void tamperedSourceFails() throws Exception {
        RuleSignatureService service = new RuleSignatureService(false, publicKeyBase64);
        String signature = sign(RULE, VERSION, SOURCE);
        assertFalse(service.verify(RULE, VERSION, SOURCE + "// injected", signature));
        assertFalse(service.verify("other_rule", VERSION, SOURCE, signature));
    }

    @Test
    void optionalModeAllowsUnsignedDraft() {
        RuleSignatureService service = new RuleSignatureService(false, publicKeyBase64);
        assertTrue(service.verify(RULE, VERSION, SOURCE, null));
        assertTrue(service.verify(RULE, VERSION, SOURCE, " "));
    }

    @Test
    void requiredModeRejectsUnsignedDraft() {
        RuleSignatureService service = new RuleSignatureService(true, publicKeyBase64);
        assertFalse(service.verify(RULE, VERSION, SOURCE, null));
    }

    @Test
    void signatureWithoutPublicKeyIsRejected() throws Exception {
        RuleSignatureService service = new RuleSignatureService(false, "");
        assertFalse(service.verify(RULE, VERSION, SOURCE, sign(RULE, VERSION, SOURCE)));
    }

    private String sign(String rule, String version, String source) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(keyPair.getPrivate());
        signer.update(RuleSignatureService.payload(rule, version, source).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signer.sign());
    }
}
