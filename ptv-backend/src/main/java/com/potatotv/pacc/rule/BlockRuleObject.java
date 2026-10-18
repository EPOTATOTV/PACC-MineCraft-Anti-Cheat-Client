package com.potatotv.pacc.rule;

import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;

import java.util.Locale;
import java.util.Map;

/**
 * PRL 方块事件宿主对象（P-09）。
 *
 * <p>26.2 的硫磺方块 / 硫磺尖刺用字符串材质表达，规则通过 {@code block.is_sulfur()}
 * 判断，不需要每加一种方块就改语言。
 */
public final class BlockRuleObject implements PrlHostObject {

    private final Map<String, Object> values;

    public BlockRuleObject(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "player_id" -> text("player_id");
            case "block_type" -> text("block_type");
            case "action" -> text("action");
            case "x" -> number("x");
            case "y" -> number("y");
            case "z" -> number("z");
            case "time_ms" -> integer("time_ms");
            default -> throw new PrlSecurityException("BlockEvent 没有字段 '" + member + "'");
        };
    }

    @Override
    public Object callMethod(String method, Object[] args) {
        String type = text("block_type").toUpperCase(Locale.ROOT);
        return switch (method) {
            case "is_sulfur" -> type.contains("SULFUR");
            case "is_ore" -> type.contains("_ORE") || type.contains("ANCIENT_DEBRIS")
                    || type.contains("SULFUR_ORE");
            default -> throw new PrlSecurityException("BlockEvent 没有方法 '" + method + "'");
        };
    }

    @Override
    public String describableMembers() {
        return "player_id, block_type, action, x, y, z, time_ms";
    }

    private String text(String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private double number(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    private int integer(String key) {
        return (int) number(key);
    }
}
