package com.potatotv.pacc.geyser.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 YAML 子集解析器，只覆盖 PACC 配置用到的写法：缩进映射、标量、字符串列表、
 * {@code #} 注释与引号包裹的值。纯 JDK 实现，不引入 YAML 依赖。
 *
 * <p>之所以自己写而不是引第三方库：pacc-geyser 是打进 Geyser 扩展 jar 的产物，
 * 配置结构固定且简单，没必要为此拖一个运行时依赖进来。
 */
public final class SimpleYaml {

    private SimpleYaml() {
    }

    private record Line(int indent, String content) {
    }

    /**
     * 解析文本。返回嵌套结构：映射为 {@code Map<String,Object>}，列表为 {@code List<Object>}，
     * 标量为 {@code String}（能解析成布尔 / 整数 / 浮点时给出对应类型）。
     */
    public static Map<String, Object> parse(String text) {
        List<Line> lines = new ArrayList<>();
        if (text != null) {
            for (String rawLine : text.split("\r?\n", -1)) {
                String line = stripComment(rawLine);
                if (line.isBlank()) {
                    continue;
                }
                lines.add(new Line(countIndent(line), line.trim()));
            }
        }
        int[] index = {0};
        return parseMap(lines, index, -1);
    }

    private static Map<String, Object> parseMap(List<Line> lines, int[] index, int parentIndent) {
        Map<String, Object> result = new LinkedHashMap<>();
        while (index[0] < lines.size()) {
            Line line = lines.get(index[0]);
            if (line.indent <= parentIndent || line.content.startsWith("- ")) {
                break;
            }
            int colon = line.content.indexOf(':');
            if (colon < 0) {
                index[0]++;
                continue;
            }
            String key = line.content.substring(0, colon).trim();
            String value = line.content.substring(colon + 1).trim();
            index[0]++;
            if (!value.isEmpty()) {
                result.put(key, scalar(value));
                continue;
            }
            if (index[0] < lines.size() && lines.get(index[0]).indent > line.indent
                    && lines.get(index[0]).content.startsWith("- ")) {
                result.put(key, parseList(lines, index));
            } else {
                result.put(key, parseMap(lines, index, line.indent));
            }
        }
        return result;
    }

    private static List<Object> parseList(List<Line> lines, int[] index) {
        List<Object> result = new ArrayList<>();
        int listIndent = lines.get(index[0]).indent;
        while (index[0] < lines.size()) {
            Line line = lines.get(index[0]);
            if (line.indent != listIndent || !line.content.startsWith("- ")) {
                break;
            }
            result.add(scalar(line.content.substring(2).trim()));
            index[0]++;
        }
        return result;
    }

    /** 去掉行内注释；只在 {@code #} 位于行首或前面是空白时才算注释，避免切到值里的 {@code #}。 */
    private static String stripComment(String line) {
        int hash = line.indexOf('#');
        if (hash < 0) {
            return line;
        }
        if (hash == 0 || Character.isWhitespace(line.charAt(hash - 1))) {
            return line.substring(0, hash);
        }
        return line;
    }

    private static int countIndent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static Object scalar(String raw) {
        String value = unquote(raw);
        if ("true".equalsIgnoreCase(value)) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(value)) {
            return Boolean.FALSE;
        }
        try {
            return Integer.valueOf(value);
        } catch (NumberFormatException ignored) {
            // 继续尝试其它数值类型。
        }
        try {
            return Double.valueOf(value);
        } catch (NumberFormatException ignored) {
            // 落回字符串。
        }
        return value;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }
}
