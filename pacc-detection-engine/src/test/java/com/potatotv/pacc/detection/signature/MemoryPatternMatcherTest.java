package com.potatotv.pacc.detection.signature;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 内存特征码匹配器测试：通配符语义、边界长度、非法模式。
 */
class MemoryPatternMatcherTest {

    private static byte[] dump(int... bytes) {
        byte[] data = new byte[bytes.length];
        for (int i = 0; i < bytes.length; i++) {
            data[i] = (byte) bytes[i];
        }
        return data;
    }

    @Test
    void 精确匹配() {
        assertTrue(MemoryPatternMatcher.contains(dump(0x48, 0x8B, 0x05), "48 8B 05"));
        assertTrue(MemoryPatternMatcher.contains(dump(0x90, 0x48, 0x8B, 0x05, 0xC3), "48 8B 05"));
        assertFalse(MemoryPatternMatcher.contains(dump(0x48, 0x8B, 0x06), "48 8B 05"));
    }

    @Test
    void 双问号通配任意字节() {
        assertTrue(MemoryPatternMatcher.contains(dump(0xDE, 0x00, 0xEF), "DE ?? EF"));
        assertTrue(MemoryPatternMatcher.contains(dump(0xDE, 0xFF, 0xEF), "DE ?? EF"));
        assertFalse(MemoryPatternMatcher.contains(dump(0xDE, 0xEF), "DE ?? EF"));
    }

    @Test
    void 单问号也是通配符() {
        assertArrayEquals(new int[]{0x48, -1, 0x05}, MemoryPatternMatcher.compile("48 ? 05"));
        assertTrue(MemoryPatternMatcher.contains(dump(0x48, 0x7F, 0x05), "48 ? 05"));
    }

    @Test
    void 全通配模式命中任意非空转储() {
        assertTrue(MemoryPatternMatcher.contains(dump(0x01, 0x02), "?? ??"));
        assertTrue(MemoryPatternMatcher.contains(dump(0x01), "??"));
        assertFalse(MemoryPatternMatcher.contains(new byte[0], "??"));
    }

    @Test
    void 转储与模式等长时可命中() {
        assertTrue(MemoryPatternMatcher.contains(dump(0x11, 0x22, 0x33), "11 22 33"));
        assertFalse(MemoryPatternMatcher.contains(dump(0x11, 0x22), "11 22 33"));
    }

    @Test
    void 模式长于转储时不命中() {
        assertFalse(MemoryPatternMatcher.contains(dump(0x48, 0x8B), "48 8B 05"));
    }

    @Test
    void 空转储与null转储不命中() {
        assertFalse(MemoryPatternMatcher.contains(new byte[0], "48"));
        assertFalse(MemoryPatternMatcher.contains(null, "48"));
        assertFalse(MemoryPatternMatcher.contains(dump(0x48), null));
    }

    @Test
    void 非法模式编译失败() {
        assertNull(MemoryPatternMatcher.compile(null));
        assertNull(MemoryPatternMatcher.compile("   "));
        assertNull(MemoryPatternMatcher.compile("GG HH"));
        assertNull(MemoryPatternMatcher.compile("4"));
        assertNull(MemoryPatternMatcher.compile("4G"));
        assertNull(MemoryPatternMatcher.compile("0x41"));
        assertFalse(MemoryPatternMatcher.contains(dump(0x48), "GG"));
    }

    @Test
    void 大小写不敏感且容忍多余空白() {
        assertArrayEquals(new int[]{0xAB, 0xCD}, MemoryPatternMatcher.compile("  ab  CD "));
        assertTrue(MemoryPatternMatcher.contains(dump(0xAB, 0xCD), "AB cd"));
    }
}
