package com.potatotv.pacc.addon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Addon 哈希特征库：已知作弊 UUID 集合、manifest/key 文件 SHA-256 集合、关键词集合。
 *
 * <p>支持从 JSON 加载，供 PTV 热更新下发。结构为：
 * <pre>{@code {"uuids":[...],"sha256":[...],"keywords":[...]}}</pre>
 * 缺失字段按空集合处理，解析失败返回空库而不抛异常——扫描端不应该因为特征库坏了就整体停摆。</p>
 */
public final class AddonSignatureStore {

    private static final long MAX_SIGNATURE_BYTES = 4L * 1024 * 1024;

    private final Set<String> cheatUuids;
    private final Set<String> cheatSha256;
    private final Set<String> cheatKeywords;

    private AddonSignatureStore(Set<String> cheatUuids, Set<String> cheatSha256, Set<String> cheatKeywords) {
        this.cheatUuids = Set.copyOf(cheatUuids);
        this.cheatSha256 = Set.copyOf(cheatSha256);
        this.cheatKeywords = Set.copyOf(cheatKeywords);
    }

    public static AddonSignatureStore empty() {
        return new AddonSignatureStore(Set.of(), Set.of(), Set.of());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Set<String> cheatUuids() {
        return cheatUuids;
    }

    public Set<String> cheatSha256() {
        return cheatSha256;
    }

    public Set<String> cheatKeywords() {
        return cheatKeywords;
    }

    public boolean isCheatUuid(String uuid) {
        return uuid != null && cheatUuids.contains(uuid.toLowerCase(Locale.ROOT));
    }

    public boolean isCheatSha256(String sha256) {
        return sha256 != null && cheatSha256.contains(sha256.toLowerCase(Locale.ROOT));
    }

    public boolean containsNameKeyword(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String kw : cheatKeywords) {
            if (!kw.isEmpty() && lower.contains(kw)) {
                return true;
            }
        }
        return false;
    }

    /** 返回 name 命中的关键词（小写），没命中则为空集合。 */
    public Set<String> nameKeywords(String name) {
        Set<String> hits = new LinkedHashSet<>();
        if (name == null || name.isEmpty()) {
            return hits;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String kw : cheatKeywords) {
            if (!kw.isEmpty() && lower.contains(kw)) {
                hits.add(kw);
            }
        }
        return hits;
    }

    /** 从 JSON 文本加载特征库；解析失败返回空库。 */
    public static AddonSignatureStore loadFromJson(String json) {
        if (json == null || json.isBlank()) {
            return empty();
        }
        try {
            Map<String, Object> root = Json.parseObject(json);
            return builder()
                    .cheatUuids(strings(root.get("uuids")))
                    .cheatSha256(strings(root.get("sha256")))
                    .cheatKeywords(strings(root.get("keywords")))
                    .build();
        } catch (RuntimeException e) {
            return empty();
        }
    }

    /**
     * 从文件加载特征库。文件缺失、超限或不可读时返回空库，不抛异常。
     */
    public static AddonSignatureStore load(Path file) {
        if (file == null) {
            return empty();
        }
        try {
            if (!Files.isRegularFile(file) || Files.size(file) > MAX_SIGNATURE_BYTES) {
                return empty();
            }
            byte[] bytes = Files.readAllBytes(file);
            return loadFromJson(new String(bytes, StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return empty();
        }
    }

    private static Set<String> strings(Object value) {
        Set<String> out = new LinkedHashSet<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                addNormalized(out, o);
            }
        } else {
            addNormalized(out, value);
        }
        return out;
    }

    private static void addNormalized(Set<String> out, Object o) {
        if (o instanceof String s && !s.isBlank()) {
            out.add(s.trim().toLowerCase(Locale.ROOT));
        }
    }

    /** 特征库构建器。集合元素统一按小写去空白归一化。 */
    public static final class Builder {
        private final Set<String> uuids = new LinkedHashSet<>();
        private final Set<String> sha256 = new LinkedHashSet<>();
        private final Set<String> keywords = new LinkedHashSet<>();

        public Builder cheatUuids(Collection<String> values) {
            addAll(uuids, values);
            return this;
        }

        public Builder cheatSha256(Collection<String> values) {
            addAll(sha256, values);
            return this;
        }

        public Builder cheatKeywords(Collection<String> values) {
            addAll(keywords, values);
            return this;
        }

        public Builder addUuid(String uuid) {
            addAll(uuids, List.of(uuid));
            return this;
        }

        public Builder addSha256(String hash) {
            addAll(sha256, List.of(hash));
            return this;
        }

        public Builder addKeyword(String keyword) {
            addAll(keywords, List.of(keyword));
            return this;
        }

        public AddonSignatureStore build() {
            return new AddonSignatureStore(uuids, sha256, keywords);
        }

        private static void addAll(Set<String> target, Collection<String> values) {
            if (values == null) {
                return;
            }
            for (String v : values) {
                if (v != null && !v.isBlank()) {
                    target.add(v.trim().toLowerCase(Locale.ROOT));
                }
            }
        }
    }
}
