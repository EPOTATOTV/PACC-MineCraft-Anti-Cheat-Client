package com.potatotv.pacc.server.core.protocol;

import com.potatotv.pacc.addon.Json;

import java.lang.System.Logger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * {@code pacc:mods} 上报协议的服务端解析器——本类即协议规格。
 *
 * <h2>通道</h2>
 * <p>通道名 {@code pacc:mods}（Bukkit plugin channel id），方向为<b>客户端 → 服务端单向</b>。服务端只接收，
 * 不在此通道回发数据。
 *
 * <h2>载荷</h2>
 * <p>UTF-8 编码的 JSON，结构固定为：
 * <pre>{@code
 * {"v":1,"mods":[{"id":"<modid>","sha256":"<64位十六进制小写>"}]}
 * }</pre>
 * <ul>
 *   <li>{@code v}：协议版本，当前为 {@code 1}；缺少时按 1 处理，出现其它版本一律丢弃。</li>
 *   <li>{@code mods}：条目数组，每项含 {@code id}（modid，可为空）与 {@code sha256}。</li>
 * </ul>
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>载荷字节数 ≤ {@value #MAX_PAYLOAD_BYTES}（64KB）。</li>
 *   <li>{@code mods} 条目数 ≤ {@value #MAX_ENTRIES}。</li>
 *   <li>{@code sha256} 必须是 {@value #SHA256_HEX_LENGTH} 位十六进制（大小写均可，解析后统一归一为小写）。</li>
 *   <li>{@code id} 可为空。</li>
 *   <li>任何畸形 / 超限载荷一律丢弃，只记服务端日志，不抛异常、不影响游戏流程。</li>
 * </ul>
 *
 * <h2>发送端</h2>
 * <p>客户端发送端由 {@code pacc-forge-mod} / {@code pacc-fabric-mod} 实现。本次仅定义协议与服务端接收，
 * 客户端发送端尚未实现。
 */
public final class ModHashParser {

    /** 客户端 → 服务端单向上报通道名。 */
    public static final String CHANNEL = "pacc:mods";

    /** 载荷字节数上限：64KB。 */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    /** 单次上报的 Mod 条目数上限。 */
    public static final int MAX_ENTRIES = 128;

    /** SHA-256 十六进制字符串长度。 */
    public static final int SHA256_HEX_LENGTH = 64;

    private static final int PROTOCOL_VERSION = 1;
    private static final Logger LOG = System.getLogger(ModHashParser.class.getName());

    private ModHashParser() {
    }

    /** 解析通道载荷（字节）。任何畸形 / 超限输入返回 {@link Optional#empty()}。 */
    public static Optional<ModHashReport> parse(byte[] payload) {
        if (payload == null || payload.length == 0) {
            return Optional.empty();
        }
        if (payload.length > MAX_PAYLOAD_BYTES) {
            return discard("载荷超限 " + payload.length + " 字节");
        }
        return parseText(new String(payload, StandardCharsets.UTF_8));
    }

    /** 解析通道载荷（字符串，UTF-8）。任何畸形 / 超限输入返回 {@link Optional#empty()}。 */
    public static Optional<ModHashReport> parse(String payload) {
        if (payload == null || payload.isEmpty()) {
            return Optional.empty();
        }
        if (payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES) {
            return discard("载荷超限");
        }
        return parseText(payload);
    }

    private static Optional<ModHashReport> parseText(String text) {
        Object root;
        try {
            root = Json.parse(text);
        } catch (RuntimeException ex) {
            // 畸形 JSON：只记日志，绝不上抛，避免恶意载荷打断服务端流程。
            return discard("JSON 解析失败: " + ex.getMessage());
        }
        if (!(root instanceof Map<?, ?> map)) {
            return discard("顶层不是 JSON 对象");
        }
        if (!versionSupported(map.get("v"))) {
            return discard("协议版本不受支持");
        }
        if (!(map.get("mods") instanceof List<?> entries)) {
            return discard("缺少 mods 数组");
        }
        if (entries.size() > MAX_ENTRIES) {
            return discard("mods 条目超限 " + entries.size());
        }
        List<ModHashReport.ModEntry> mods = new ArrayList<>(entries.size());
        for (Object item : entries) {
            ModHashReport.ModEntry entry = parseEntry(item);
            if (entry == null) {
                return discard("存在非法 mods 条目");
            }
            mods.add(entry);
        }
        return Optional.of(new ModHashReport(mods));
    }

    private static ModHashReport.ModEntry parseEntry(Object item) {
        if (!(item instanceof Map<?, ?> entryMap)) {
            return null;
        }
        if (!(entryMap.get("sha256") instanceof String sha256)) {
            return null;
        }
        String normalized = normalizeSha256(sha256);
        if (normalized == null) {
            return null;
        }
        Object id = entryMap.get("id");
        return new ModHashReport.ModEntry(id instanceof String s ? s : null, normalized);
    }

    /** 归一为小写并校验为 64 位十六进制；非法返回 {@code null}。供各平台配置 loader 复用同一套规则。 */
    public static String normalizeSha256(String raw) {
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
        return PROTOCOL_VERSION == toIntOrMinusOne(String.valueOf(version));
    }

    private static int toIntOrMinusOne(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException ex) {
            return -1;
        }
    }

    private static <T> Optional<T> discard(String reason) {
        LOG.log(Logger.Level.WARNING, "丢弃 pacc:mods 载荷: " + reason);
        return Optional.empty();
    }
}
