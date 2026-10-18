package com.potatotv.pacc.server.core.model;

import java.util.Map;

/**
 * 数据包的字段视图。检查项只读 {@code fields}，不接触平台数据包对象。
 */
public record PacketView(
        String playerId,
        String packetType,
        long timestampMs,
        Map<String, String> fields) {

    /** 读取数值字段，缺失或不可解析时返回 {@code def}。 */
    public double num(String key, double def) {
        String raw = fields == null ? null : fields.get(key);
        if (raw == null) {
            return def;
        }
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    /** 读取布尔字段，缺失或不可识别时返回 {@code def}。 */
    public boolean bool(String key, boolean def) {
        String raw = fields == null ? null : fields.get(key);
        if (raw == null) {
            return def;
        }
        return "true".equalsIgnoreCase(raw) || "1".equals(raw);
    }

    /** 读取字符串字段，缺失时返回 null。 */
    public String str(String key) {
        return fields == null ? null : fields.get(key);
    }

    /** 字段是否存在。 */
    public boolean has(String key) {
        return fields != null && fields.containsKey(key);
    }
}
