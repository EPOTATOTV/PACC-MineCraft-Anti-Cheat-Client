package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintType;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * 指纹包的编解码、校验与应用（技术文档 §5.5 U-09）。
 *
 * <p>格式是行式文本，人可读、diff 友好、不引第三方库。第一行是版本，其后每行一条指纹，
 * 字段用制表符分隔，列表字段内部用 {@code |} 分隔，最后一行是校验和。
 *
 * <p>为什么校验和只盖正文不盖自己：校验和行必须能独立验证，否则改一位就会自洽。
 *
 * <p>为什么升级是「整包替换」而不是「增量追加」：指纹库的半更新状态最难排查——旧条目还在、
 * 新条目进来一半、校验和又对不上。整包替换让「这一版到底有哪些条目」始终可回答。
 */
public final class SignaturePackCodec {

    /** 格式标识，改字段结构时必须升版本。 */
    public static final String FORMAT = "pacc-signature-pack-v1";

    private static final String FIELD_SEPARATOR = "\t";
    private static final String LIST_SEPARATOR = "|";
    private static final String VERSION_PREFIX = "version=";
    private static final String CHECKSUM_PREFIX = "checksum=";

    private SignaturePackCodec() {
    }

    /** 把指纹集合编码成包文本。值里含制表符或 {@code |} 时直接拒绝——编码前先修数据。 */
    public static String encode(String version, List<CheatFingerprint> fingerprints) {
        List<CheatFingerprint> entries = fingerprints == null ? List.of() : fingerprints;
        StringBuilder body = new StringBuilder(256 + entries.size() * 128);
        body.append(FORMAT).append('\n');
        body.append(VERSION_PREFIX).append(version).append('\n');
        for (CheatFingerprint fp : entries) {
            body.append(row(fp)).append('\n');
        }
        String checksum = checksum(body.toString());
        return body + CHECKSUM_PREFIX + checksum + "\n";
    }

    /**
     * 解析并校验包文本。
     *
     * @throws IllegalArgumentException 格式不识别、字段缺失、或校验和不匹配
     */
    public static SignaturePack decode(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("指纹包为空");
        }
        String[] lines = text.split("\n", -1);
        if (lines.length < 3 || !FORMAT.equals(lines[0].trim())) {
            throw new IllegalArgumentException("不认识的指纹包格式: " + (lines.length == 0 ? "" : lines[0]));
        }
        String version = null;
        String checksum = null;
        StringBuilder body = new StringBuilder(text.length());
        List<CheatFingerprint> fingerprints = new ArrayList<>();

        body.append(FORMAT).append('\n');
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith(CHECKSUM_PREFIX)) {
                checksum = trimmed.substring(CHECKSUM_PREFIX.length()).trim();
                continue;
            }
            if (checksum != null) {
                throw new IllegalArgumentException("校验和行之后不应还有内容");
            }
            if (trimmed.startsWith(VERSION_PREFIX)) {
                version = trimmed.substring(VERSION_PREFIX.length()).trim();
                body.append(VERSION_PREFIX).append(version).append('\n');
                continue;
            }
            fingerprints.add(parseRow(line));
            body.append(line).append('\n');
        }
        if (version == null || checksum == null) {
            throw new IllegalArgumentException("指纹包缺少版本或校验和");
        }
        String expected = checksum(body.toString());
        if (!expected.equals(checksum)) {
            throw new IllegalArgumentException(
                    "指纹包校验和不匹配（期望 " + expected + "，实际 " + checksum + "），包可能被改坏或截断");
        }
        return new SignaturePack(version, checksum, fingerprints);
    }

    /**
     * 用包内容整体替换库内指纹。
     *
     * @return 替换前的快照，供回滚使用
     */
    public static List<CheatFingerprint> apply(SignaturePack pack, SignatureLibrary library) {
        if (pack == null || library == null) {
            throw new IllegalArgumentException("pack 与 library 不能为空");
        }
        List<CheatFingerprint> previous = library.fingerprints();
        library.replaceAll(pack.fingerprints());
        return previous;
    }

    /** 回滚到之前的快照。 */
    public static void rollback(List<CheatFingerprint> previous, SignatureLibrary library) {
        if (library == null) {
            throw new IllegalArgumentException("library 不能为空");
        }
        library.replaceAll(previous == null ? List.of() : previous);
    }

    /** 内容校验和（SHA-256 前 8 字节十六进制）。 */
    public static String checksum(String body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] full = digest.digest((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
            byte[] head = new byte[8];
            System.arraycopy(full, 0, head, 0, head.length);
            return HexFormat.of().formatHex(head);
        } catch (NoSuchAlgorithmException ex) {
            // JDK 必须自带 SHA-256；走到这里说明运行环境被裁剪过，直接失败而不是退化成弱校验
            throw new IllegalStateException("当前 JDK 不支持 SHA-256", ex);
        }
    }

    private static String row(CheatFingerprint fp) {
        return String.join(FIELD_SEPARATOR,
                requireSafe(fp.id(), "id"),
                fp.type().name(),
                requireSafe(fp.name(), "name"),
                fp.category().name(),
                Integer.toString(fp.riskLevel()),
                joinList(fp.processNames()),
                joinList(fp.windowClasses()),
                joinList(fp.windowTitles()),
                joinList(fp.moduleNames()),
                joinList(fp.fileHashes()),
                joinList(fp.memoryPatterns()));
    }

    private static CheatFingerprint parseRow(String line) {
        String[] parts = line.split(FIELD_SEPARATOR, -1);
        if (parts.length != 11) {
            throw new IllegalArgumentException("指纹行字段数应为 11，实际 " + parts.length + ": " + line);
        }
        try {
            return new CheatFingerprint(
                    parts[0],
                    FingerprintType.valueOf(parts[1]),
                    parts[2],
                    splitList(parts[5]),
                    splitList(parts[6]),
                    splitList(parts[7]),
                    splitList(parts[8]),
                    splitList(parts[9]),
                    splitList(parts[10]),
                    FingerprintCategory.valueOf(parts[3]),
                    Integer.parseInt(parts[4]));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("指纹行解析失败: " + ex.getMessage(), ex);
        }
    }

    private static String requireSafe(String value, String field) {
        if (value == null) {
            return "";
        }
        if (value.contains(FIELD_SEPARATOR) || value.contains(LIST_SEPARATOR) || value.contains("\n")) {
            throw new IllegalArgumentException("字段 " + field + " 含保留分隔符，无法编码: " + value);
        }
        return value;
    }

    private static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "";
        }
        List<String> safe = new ArrayList<>(values.size());
        for (String value : values) {
            safe.add(requireSafe(value, "list"));
        }
        return String.join(LIST_SEPARATOR, safe);
    }

    private static List<String> splitList(String raw) {
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (String value : raw.split("\\" + LIST_SEPARATOR, -1)) {
            if (!value.isEmpty()) {
                values.add(value);
            }
        }
        return values;
    }
}
