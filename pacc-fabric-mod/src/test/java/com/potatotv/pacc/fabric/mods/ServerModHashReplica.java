package com.potatotv.pacc.fabric.mods;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 与服务端 {@code com.potatotv.pacc.server.core.protocol.ModHashParser} 口径对齐的独立实现。
 *
 * <p>模组工程是独立构建，测试拿不到 pacc-server-core 的产物，因此在这里按服务端协议复刻一份
 * 解析逻辑做交叉校验：常量、字段结构、归一化与丢弃规则都跟服务端保持一致，任何畸形 / 超限载荷
 * 一律返回 {@link Optional#empty()}。端到端用例把模组侧产出的字节喂给本类，验证两端对
 * {@code pacc:mods} 通道的理解一致。
 *
 * <p>本通道载荷是 UTF-8 JSON，没有二进制魔数；承担「版本识别」职责的是版本字段 {@code v}，
 * 承担「长度约束」职责的是 64KB 载荷上限与 128 条目上限。
 */
final class ServerModHashReplica {

    /** 客户端 → 服务端单向上报通道名。 */
    static final String CHANNEL = "pacc:mods";

    /** 载荷字节数上限：64KB。 */
    static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /** 单次上报的 Mod 条目数上限。 */
    static final int MAX_ENTRIES = 128;

    /** SHA-256 十六进制字符串长度。 */
    static final int SHA256_HEX_LENGTH = 64;

    static final int PROTOCOL_VERSION = 1;

    private ServerModHashReplica() {
    }

    /** 解析后的单条条目；{@code id} 可为 null，{@code sha256} 已归一为小写。 */
    record Entry(String id, String sha256) {
    }

    static Optional<List<Entry>> parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return Optional.empty();
        }
        return parseText(new String(payload, StandardCharsets.UTF_8));
    }

    static Optional<List<Entry>> parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            return Optional.empty();
        }
        return parseText(payload);
    }

    private static Optional<List<Entry>> parseText(String text) {
        Object root;
        try {
            root = new JsonReader(text).parseDocument();
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
        if (!(root instanceof Map<?, ?> map)) {
            return Optional.empty();
        }
        if (!versionSupported(map.get("v"))) {
            return Optional.empty();
        }
        if (!(map.get("mods") instanceof List<?> entries)) {
            return Optional.empty();
        }
        if (entries.size() > MAX_ENTRIES) {
            return Optional.empty();
        }
        List<Entry> mods = new ArrayList<>(entries.size());
        for (Object item : entries) {
            Entry entry = parseEntry(item);
            if (entry == null) {
                return Optional.empty();
            }
            mods.add(entry);
        }
        return Optional.of(List.copyOf(mods));
    }

    private static Entry parseEntry(Object item) {
        if (!(item instanceof Map<?, ?> entryMap)) {
            return null;
        }
        if (!(entryMap.get("sha256") instanceof String raw)) {
            return null;
        }
        String normalized = normalizeSha256(raw);
        if (normalized == null) {
            return null;
        }
        Object id = entryMap.get("id");
        return new Entry(id instanceof String s ? s : null, normalized);
    }

    /** 归一为小写并校验为 64 位十六进制；非法返回 {@code null}。 */
    static String normalizeSha256(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim();
        if (value.length() != SHA256_HEX_LENGTH) {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return null;
            }
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** 缺少 {@code v} 视为版本 1；出现其它版本视为不兼容。 */
    private static boolean versionSupported(Object version) {
        if (version == null) {
            return true;
        }
        if (version instanceof Number number) {
            return number.intValue() == PROTOCOL_VERSION;
        }
        try {
            return PROTOCOL_VERSION == Integer.parseInt(String.valueOf(version).trim());
        } catch (NumberFormatException ex) {
            return false;
        }
    }

    /** 极简 JSON 解码：只覆盖本协议用得到的对象 / 数组 / 字符串 / 数字 / 布尔 / null。 */
    private static final class JsonReader {

        private final String text;
        private int pos;

        JsonReader(String text) {
            this.text = text;
        }

        Object parseDocument() {
            Object value = parseValue();
            skipWhitespace();
            if (pos < text.length()) {
                throw new IllegalArgumentException("JSON 末尾存在多余字符");
            }
            return value;
        }

        private Object parseValue() {
            skipWhitespace();
            if (pos >= text.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            return switch (text.charAt(pos)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> object = new LinkedHashMap<>();
            expect('{');
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return object;
            }
            while (true) {
                skipWhitespace();
                String key = parseString();
                skipWhitespace();
                expect(':');
                object.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return object;
                } else {
                    throw new IllegalArgumentException("JSON 对象期望 ',' 或 '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("JSON 数组期望 ',' 或 ']'");
                }
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder out = new StringBuilder();
            while (pos < text.length()) {
                char c = text.charAt(pos++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw new IllegalArgumentException("JSON 字符串尾随反斜杠");
                    }
                    char esc = text.charAt(pos++);
                    switch (esc) {
                        case '"' -> out.append('"');
                        case '\\' -> out.append('\\');
                        case '/' -> out.append('/');
                        case 'b' -> out.append('\b');
                        case 'f' -> out.append('\f');
                        case 'n' -> out.append('\n');
                        case 'r' -> out.append('\r');
                        case 't' -> out.append('\t');
                        case 'u' -> out.append(parseUnicode());
                        default -> throw new IllegalArgumentException("未知转义 \\" + esc);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        private char parseUnicode() {
            if (pos + 4 > text.length()) {
                throw new IllegalArgumentException("JSON \\u 转义长度不足");
            }
            char value = (char) Integer.parseInt(text.substring(pos, pos + 4), 16);
            pos += 4;
            return value;
        }

        private Object parseLiteral(String literal, Object value) {
            if (text.regionMatches(pos, literal, 0, literal.length())) {
                pos += literal.length();
                return value;
            }
            throw new IllegalArgumentException("非法字面量");
        }

        private Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < text.length() && isNumberChar(text.charAt(pos))) {
                pos++;
            }
            String raw = text.substring(start, pos);
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("非法数字");
            }
            if (raw.contains(".") || raw.contains("e") || raw.contains("E")) {
                return Double.parseDouble(raw);
            }
            try {
                return Long.parseLong(raw);
            } catch (NumberFormatException ex) {
                return Double.parseDouble(raw);
            }
        }

        private static boolean isNumberChar(char c) {
            return Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private void expect(char c) {
            skipWhitespace();
            if (pos >= text.length() || text.charAt(pos) != c) {
                throw new IllegalArgumentException("期望 '" + c + "'");
            }
            pos++;
        }

        private char peek() {
            skipWhitespace();
            return pos >= text.length() ? '\0' : text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
