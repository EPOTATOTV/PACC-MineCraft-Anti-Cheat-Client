package com.potatotv.pacc.addon;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解码器，只覆盖本模块所需结构，避免引入第三方依赖。
 *
 * <p>递归下降解析，支持对象/数组/字符串/数字/布尔/null。带解析深度上限，
 * 防止畸形或恶意构造的深层嵌套把调用栈打爆。</p>
 */
public final class Json {

    /** 默认解析深度上限，超过即视为畸形输入。 */
    public static final int DEFAULT_MAX_DEPTH = 64;

    private Json() {
    }

    /** 用默认深度上限解析完整 JSON 文档，返回 Object（Map / List / String / Number / Boolean / null）。 */
    public static Object parse(String json) {
        return parse(json, DEFAULT_MAX_DEPTH);
    }

    /** 指定深度上限解析完整 JSON 文档。 */
    public static Object parse(String json, int maxDepth) {
        if (json == null) {
            return null;
        }
        Parser p = new Parser(json, maxDepth);
        Object v = p.parseValue(0);
        p.skipWs();
        if (p.pos < p.s.length()) {
            throw new IllegalArgumentException("JSON 末尾存在多余字符");
        }
        return v;
    }

    /** 便捷解析为对象（顶层必须是 {}，否则抛异常）。 */
    public static Map<String, Object> parseObject(String json) {
        Object v = parse(json);
        if (!(v instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("期望 JSON 对象");
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> out = (Map<String, Object>) m;
        return out;
    }

    private static final class Parser {
        private final String s;
        private final int maxDepth;
        private int pos;

        Parser(String s, int maxDepth) {
            this.s = s;
            this.maxDepth = maxDepth;
            this.pos = 0;
        }

        Object parseValue(int depth) {
            if (depth > maxDepth) {
                throw new IllegalArgumentException("JSON 嵌套过深");
            }
            skipWs();
            if (pos >= s.length()) {
                throw new IllegalArgumentException("JSON 意外结束");
            }
            char c = s.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth);
                case '[' -> parseArray(depth);
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        Map<String, Object> parseObject(int depth) {
            Map<String, Object> m = new LinkedHashMap<>();
            expect('{');
            skipWs();
            if (peek() == '}') {
                pos++;
                return m;
            }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                Object value = parseValue(depth + 1);
                m.put(key, value);
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return m;
                } else {
                    throw new IllegalArgumentException("JSON 对象期望 ',' 或 '}'，实际=" + c);
                }
            }
        }

        List<Object> parseArray(int depth) {
            List<Object> list = new ArrayList<>();
            expect('[');
            skipWs();
            if (peek() == ']') {
                pos++;
                return list;
            }
            while (true) {
                list.add(parseValue(depth + 1));
                skipWs();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return list;
                } else {
                    throw new IllegalArgumentException("JSON 数组期望 ',' 或 ']'，实际=" + c);
                }
            }
        }

        String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= s.length()) {
                        throw new IllegalArgumentException("JSON 字符串尾随反斜杠");
                    }
                    char esc = s.charAt(pos++);
                    sb.append(switch (esc) {
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        case 'b' -> '\b';
                        case 'f' -> '\f';
                        case 'n' -> '\n';
                        case 'r' -> '\r';
                        case 't' -> '\t';
                        case 'u' -> parseUnicode();
                        default -> throw new IllegalArgumentException("未知转义 \\" + esc);
                    });
                } else {
                    sb.append(c);
                }
            }
            throw new IllegalArgumentException("JSON 字符串未闭合");
        }

        private char parseUnicode() {
            if (pos + 4 > s.length()) {
                throw new IllegalArgumentException("JSON \\u 转义长度不足");
            }
            String hex = s.substring(pos, pos + 4);
            pos += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        Object parseLiteral(String lit, Object val) {
            if (s.regionMatches(pos, lit, 0, lit.length())) {
                pos += lit.length();
                return val;
            }
            throw new IllegalArgumentException("非法字面量@" + pos);
        }

        Number parseNumber() {
            int start = pos;
            if (peek() == '-') {
                pos++;
            }
            while (pos < s.length() && isNumberChar(s.charAt(pos))) {
                pos++;
            }
            String num = s.substring(start, pos);
            if (num.isEmpty()) {
                throw new IllegalArgumentException("非法数字@" + start);
            }
            if (num.contains(".") || num.contains("e") || num.contains("E")) {
                return Double.parseDouble(num);
            }
            try {
                return Long.parseLong(num);
            } catch (NumberFormatException e) {
                return Double.parseDouble(num);
            }
        }

        private static boolean isNumberChar(char c) {
            return Character.isDigit(c) || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-';
        }

        private void expect(char c) {
            skipWs();
            if (pos >= s.length() || s.charAt(pos) != c) {
                throw new IllegalArgumentException("期望 '" + c + "'@" + pos + " 实际=" + peek());
            }
            pos++;
        }

        private char peek() {
            skipWs();
            if (pos >= s.length()) {
                return '\0';
            }
            return s.charAt(pos);
        }

        private void skipWs() {
            while (pos < s.length()) {
                char c = s.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                    pos++;
                } else {
                    break;
                }
            }
        }
    }
}
