package com.potatotv.pacc.fabric.mods;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code pacc:mods} 上报载荷的组装器（与加载器无关，不引用任何 Minecraft 类）。
 *
 * <p>模组侧只负责这件事：把已装模组的文件内容算成 sha256，组装成服务端 {@code ModHashParser}
 * 能接受的 UTF-8 JSON 报文。通道名、字段结构与上限见服务端协议定义；本类与协议同版本。
 *
 * <p>为保证确定性，输出与输入顺序无关：id 先经 {@link #normalizeId(String)} 规范化，再按
 * (id, sha256) 升序排序，最后去掉完全重复的条目。条目数超过 {@value #MAX_ENTRIES} 时按排序结果
 * 截断，宁可丢尾部也不产出服务端必定丢弃的超限报文。内容为 null 的条目无法计算哈希，直接跳过。
 *
 * <p>id 只是给人看的标识（modid，或退化情况下的模组文件名 / 相对路径），服务端不据此判定作弊，
 * 因此规范化只影响报文可比性，不影响检测：不同客户端在 Windows / Linux 下拿到的路径分隔符与
 * 文件名大小写不一致时，规范化后仍会得到同一份清单。
 */
public final class ModHashPayloadBuilder {

    /** 协议版本，与 {@code ModHashParser} 的当前版本一致。 */
    public static final int PROTOCOL_VERSION = 1;

    /** 单次上报条目数上限，须与 {@code ModHashParser.MAX_ENTRIES} 保持一致。 */
    public static final int MAX_ENTRIES = 128;

    /** 载荷字节数上限，须与 {@code ModHashParser.MAX_PAYLOAD_BYTES} 保持一致。 */
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

    private ModHashPayloadBuilder() {
    }

    /** 组装 UTF-8 JSON 报文。传入 {@code null} 视为空清单。 */
    public static String build(List<InstalledMod> mods) {
        List<Entry> entries = collect(mods);
        StringBuilder json = new StringBuilder(entries.size() * 120 + 24);
        json.append("{\"v\":").append(PROTOCOL_VERSION).append(",\"mods\":[");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                json.append(',');
            }
            Entry entry = entries.get(i);
            json.append("{\"id\":\"").append(escape(entry.id()))
                    .append("\",\"sha256\":\"").append(entry.sha256()).append("\"}");
        }
        return json.append("]}").toString();
    }

    /** 组装报文的字节形式，即实际通过通道发送的内容。 */
    public static byte[] buildBytes(List<InstalledMod> mods) {
        return build(mods).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 规范化模组标识：去首尾空白、路径分隔符统一成 {@code '/'}、统一小写（{@link Locale#ROOT}）。
     *
     * <p>id 为空（loader 未提供或读取失败）时返回空字符串。
     */
    public static String normalizeId(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().replace('\\', '/').toLowerCase(Locale.ROOT);
    }

    /** 计算内容的 sha256 小写十六进制串。 */
    public static String sha256Hex(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(content));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 是 JVM 必须提供的算法，走到这里说明运行环境异常。
            throw new IllegalStateException("JVM 缺失 SHA-256 实现", ex);
        }
    }

    private static List<Entry> collect(List<InstalledMod> mods) {
        if (mods == null || mods.isEmpty()) {
            return List.of();
        }
        List<Entry> sorted = new ArrayList<>(mods.size());
        for (InstalledMod mod : mods) {
            if (mod == null || mod.content() == null) {
                continue;
            }
            sorted.add(new Entry(normalizeId(mod.id()), sha256Hex(mod.content())));
        }
        sorted.sort(Comparator.comparing(Entry::id).thenComparing(Entry::sha256));

        // 同 id 不同内容可能是两个真实模组（例如 id 缺失时），只有 id 与哈希都相同才算重复。
        List<Entry> unique = new ArrayList<>(sorted.size());
        Set<String> seen = new HashSet<>();
        for (Entry entry : sorted) {
            if (seen.add(entry.id() + '\u0000' + entry.sha256())) {
                unique.add(entry);
            }
        }
        if (unique.size() > MAX_ENTRIES) {
            unique = unique.subList(0, MAX_ENTRIES);
        }
        return unique;
    }

    /** 只转义 JSON 字符串里必须转义的引号；反斜杠已被 {@link #normalizeId(String)} 换成 {@code '/'}。 */
    private static String escape(String value) {
        return value.indexOf('"') < 0 ? value : value.replace("\"", "\\\"");
    }

    private record Entry(String id, String sha256) {
    }

    /**
     * 一个已装模组：标识与文件内容。
     *
     * @param id      modid；loader 拿不到时可为文件名或相对路径（写入载荷前会先规范化），未知时为 null
     * @param content 模组 jar 的字节（含签名与指纹文件，保证整包内容一致）
     */
    public record InstalledMod(String id, byte[] content) {
    }
}
