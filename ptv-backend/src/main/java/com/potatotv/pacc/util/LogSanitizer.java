package com.potatotv.pacc.util;

/**
 * 日志注入防护：用户可控值写入日志前，先清掉换行与控制字符，避免伪造日志行（log forging）。
 * <p>只做字符清理，不改变日志内容语义。</p>
 */
public final class LogSanitizer {

    private LogSanitizer() {
    }

    /** 去除 CR/LF 及其余控制字符；null 原样返回。 */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= 0x20 && c != 0x7f) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 逐个清理可变参数里的字符串（非字符串原样保留），便于整体传入日志占位符。 */
    public static Object[] clean(Object... args) {
        Object[] out = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            out[i] = args[i] instanceof String s ? clean(s) : args[i];
        }
        return out;
    }
}
