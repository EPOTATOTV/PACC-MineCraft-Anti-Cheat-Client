package com.potatotv.pacc.bedrock;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockDetectMathTest {

    private static final double DELTA = 1e-6;

    @Test
    void facingStraightAtTargetReturnsZero() {
        assertEquals(0.0, BedrockDetectMath.viewAngleDeg(0, 0, 0, 0, 5), DELTA);
    }

    @Test
    void facingAwayFromTargetReturns180() {
        assertEquals(180.0, BedrockDetectMath.viewAngleDeg(0, 0, 0, 0, -5), DELTA);
    }

    @Test
    void targetToTheSideReturns90() {
        // yaw=0 面向 +Z，目标在 +X 方向，夹角应为 90°。
        assertEquals(90.0, BedrockDetectMath.viewAngleDeg(0, 0, 5, 0, 0), DELTA);
    }

    @Test
    void zeroDirectionVectorReturnsZero() {
        assertEquals(0.0, BedrockDetectMath.viewAngleDeg(45, 30, 0, 0, 0), DELTA);
    }

    @Test
    void decodeBrandStripsLengthPrefix() {
        String brand = "vanilla";
        byte[] raw = brand.getBytes(StandardCharsets.UTF_8);
        byte[] payload = new byte[raw.length + 1];
        payload[0] = (byte) raw.length;
        System.arraycopy(raw, 0, payload, 1, raw.length);

        assertEquals(brand, BedrockDetectMath.decodeBrand(payload));
    }

    @Test
    void decodeBrandHandlesMalformedPayload() {
        assertEquals("", BedrockDetectMath.decodeBrand(null));
        assertEquals("", BedrockDetectMath.decodeBrand(new byte[0]));
        // 声明长度 10 但只有 2 字节正文，属越界载荷。
        assertEquals("", BedrockDetectMath.decodeBrand(new byte[]{10, 65, 66}));
    }
}
