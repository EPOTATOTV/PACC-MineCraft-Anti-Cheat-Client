package com.potatotv.pacc.detection.signature;

/**
 * 内存特征码匹配器：支持 {@code ??} 通配字节的十六进制模式（技术文档 §6.1 / §3.2 内存签名扫描）。
 *
 * <p>模式示例：{@code "48 8B 05 ?? ?? ?? ?? 89 44 24"}。
 */
public final class MemoryPatternMatcher {

    private MemoryPatternMatcher() {
    }

    /** 内存转储中是否包含给定模式。 */
    public static boolean contains(byte[] dump, String pattern) {
        int[] bytes = compile(pattern);
        if (bytes == null || dump == null || dump.length < bytes.length) {
            return false;
        }
        outer:
        for (int offset = 0; offset <= dump.length - bytes.length; offset++) {
            for (int i = 0; i < bytes.length; i++) {
                if (bytes[i] >= 0 && (dump[offset + i] & 0xFF) != bytes[i]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /** 把模式串编译为字节数组，{@code ??} 编译为 -1；非法模式返回 null。 */
    static int[] compile(String pattern) {
        if (pattern == null || pattern.isBlank()) {
            return null;
        }
        String[] tokens = pattern.trim().split("\\s+");
        int[] bytes = new int[tokens.length];
        for (int i = 0; i < tokens.length; i++) {
            String t = tokens[i];
            if ("??".equals(t) || "?".equals(t)) {
                bytes[i] = -1;
            } else if (t.length() == 2 && isHex(t)) {
                bytes[i] = Integer.parseInt(t, 16);
            } else {
                return null;
            }
        }
        return bytes;
    }

    private static boolean isHex(String s) {
        for (char c : s.toCharArray()) {
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
