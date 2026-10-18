package com.potatotv.pacc.rule;

import com.potatotv.prl.runtime.PrlHostObject;
import com.potatotv.prl.runtime.PrlSecurityException;

import java.util.Locale;
import java.util.Map;

/**
 * PRL 包级事件宿主对象（P-09）。
 *
 * <p>规则写法是 {@code packet.is_movement()} / {@code packet.length}，而不是把字段摊平。
 * 这里把宿主上下文里的 {@code packet} map 包成对象，沙箱内不经过反射。
 */
public final class PacketRuleObject implements PrlHostObject {

    private final Map<String, Object> values;

    public PacketRuleObject(Map<String, Object> values) {
        this.values = values == null ? Map.of() : values;
    }

    @Override
    public Object getMember(String member) {
        return switch (member) {
            case "player_id" -> text("player_id");
            case "packet_type" -> text("packet_type");
            case "length" -> integer("length");
            case "arrival_ms" -> integer("arrival_ms");
            case "client_ms" -> integer("client_ms");
            case "sequence" -> integer("sequence");
            default -> throw new PrlSecurityException("PacketEvent 没有字段 '" + member + "'");
        };
    }

    @Override
    public Object callMethod(String method, Object[] args) {
        String type = text("packet_type").toUpperCase(Locale.ROOT);
        return switch (method) {
            case "is_movement" -> type.contains("POSITION") || type.contains("MOVE") || type.contains("FLY");
            case "is_combat" -> type.contains("ATTACK") || type.contains("INTERACT")
                    || type.contains("ANIMATION") || type.contains("DIGGING");
            case "is_placement" -> type.contains("PLACEMENT") || type.contains("BLOCK_PLACE");
            default -> throw new PrlSecurityException("PacketEvent 没有方法 '" + method + "'");
        };
    }

    @Override
    public String describableMembers() {
        return "player_id, packet_type, length, arrival_ms, client_ms, sequence";
    }

    private String text(String key) {
        Object value = values.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private int integer(String key) {
        Object value = values.get(key);
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return 0;
        }
    }
}
