package com.potatotv.pacc.addon;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * 基岩版 com.mojang 目录的 Addon 本地扫描器。
 *
 * <p>只读取玩家本机文件，产出 {@link ScannedAddon} 列表与 {@link WorldBinding} 列表，
 * 不做任何封禁、踢出或上报动作——处置交给上游。</p>
 *
 * <p>健壮性：对文件大小、解析深度、单包文件数都设了上限；任何异常都被吞掉并跳过，
 * 单个坏包或坏文件不会中断整次扫描。</p>
 */
public final class AddonScanner {

    private static final String BEHAVIOR_DIR = "behavior_packs";
    private static final String RESOURCE_DIR = "resource_packs";
    private static final String WORLDS_DIR = "minecraftWorlds";
    private static final String MANIFEST = "manifest.json";

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final long MAX_SCRIPT_BYTES = 2L * 1024 * 1024;
    private static final int MAX_FILES_PER_PACK = 4096;

    /** 疑似作弊的行为组件（归一化为小写后按 key 匹配）。 */
    private static final Set<String> SUSPICIOUS_COMPONENTS = Set.of(
            "minecraft:movement",
            "minecraft:attack",
            "minecraft:physics",
            "minecraft:damage_sensor",
            "minecraft:knockback_resistance");

    /** 脚本文件里命中的作弊关键词。 */
    private static final Set<String> CHEAT_SCRIPT_KEYWORDS = Set.of(
            "fly", "speed", "killaura", "kill_aura", "gamemode", "game_mode",
            "noclip", "esp", "aimbot", "autoclicker", "auto_clicker",
            "xray", "x_ray", "godmode", "god_mode", "flight", "teleport",
            "scaffold", "nofall", "no_fall");

    private static final Set<String> XRAY_KEYWORDS = Set.of(
            "xray", "x-ray", "x_ray", "wallhack", "see_through", "seethrough",
            "transparent", "ore_vision", "orevision");

    private static final Set<String> FULLBRIGHT_KEYWORDS = Set.of(
            "fullbright", "full_bright", "full-bright", "night_vision",
            "nightvision", "nofog", "no_fog");

    private final AddonSignatureStore signatures;

    public AddonScanner() {
        this(AddonSignatureStore.empty());
    }

    public AddonScanner(AddonSignatureStore signatures) {
        this.signatures = signatures == null ? AddonSignatureStore.empty() : signatures;
    }

    /** 扫描指定 com.mojang 根目录，返回 Addon 与世界绑定。根目录无效时返回空报告。 */
    public ScanReport scan(Path comMojangRoot) {
        List<ScannedAddon> addons = new ArrayList<>();
        List<WorldBinding> worlds = new ArrayList<>();
        if (comMojangRoot == null || !Files.isDirectory(comMojangRoot)) {
            return new ScanReport(addons, worlds);
        }
        addons.addAll(scanPackDir(comMojangRoot.resolve(BEHAVIOR_DIR), PackKind.BEHAVIOR));
        addons.addAll(scanPackDir(comMojangRoot.resolve(RESOURCE_DIR), PackKind.RESOURCE));
        worlds.addAll(scanWorlds(comMojangRoot.resolve(WORLDS_DIR)));
        return new ScanReport(List.copyOf(addons), List.copyOf(worlds));
    }

    private List<ScannedAddon> scanPackDir(Path dir, PackKind kind) {
        List<ScannedAddon> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        List<Path> packs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isDirectory).forEach(packs::add);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        packs.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (Path pack : packs) {
            try {
                ScannedAddon scanned = scanPack(pack, kind);
                if (scanned != null) {
                    out.add(scanned);
                }
            } catch (RuntimeException e) {
                // 单个坏包吞掉，继续扫其余包
            }
        }
        return out;
    }

    private ScannedAddon scanPack(Path packDir, PackKind kind) {
        Path manifest = packDir.resolve(MANIFEST);
        byte[] manifestBytes = readBounded(manifest, MAX_FILE_BYTES);
        if (manifestBytes == null) {
            return null;
        }
        Map<String, Object> root;
        try {
            root = Json.parseObject(decode(manifestBytes));
        } catch (RuntimeException e) {
            // 畸形清单：跳过该包
            return null;
        }

        Set<String> moduleTypes = new LinkedHashSet<>();
        String uuid = null;
        String name = null;
        String version = null;
        if (root.get("header") instanceof Map<?, ?> header) {
            uuid = asString(header.get("uuid"));
            name = asString(header.get("name"));
            version = asVersion(header.get("version"));
        }
        if (root.get("modules") instanceof List<?> modules) {
            for (Object module : modules) {
                if (module instanceof Map<?, ?> m) {
                    String type = asString(m.get("type"));
                    if (type != null) {
                        moduleTypes.add(type.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }

        String sha256 = sha256Hex(manifestBytes);
        Set<String> components = new LinkedHashSet<>();
        Set<String> scriptKeywords = new LinkedHashSet<>();
        if (kind == PackKind.BEHAVIOR) {
            components.addAll(scanBehaviorComponents(packDir));
            scriptKeywords.addAll(scanScripts(packDir));
        } else {
            components.addAll(scanResourceHeuristics(packDir, name));
        }

        boolean knownCheat = signatures.isCheatUuid(uuid) || signatures.isCheatSha256(sha256);
        for (String keyword : signatures.nameKeywords(name)) {
            components.add("keyword:" + keyword);
        }
        boolean suspicious = knownCheat || !components.isEmpty() || !scriptKeywords.isEmpty();

        return new ScannedAddon(uuid, name, version, kind, packDir.toAbsolutePath(),
                sha256, moduleTypes, scriptKeywords, components, suspicious, knownCheat);
    }

    /** 扫描 behavior_packs/<pack>/entities 下 JSON，收集命中的可疑组件 key。 */
    private Set<String> scanBehaviorComponents(Path packDir) {
        Set<String> found = new LinkedHashSet<>();
        Path entities = packDir.resolve("entities");
        walkFiles(entities, file -> {
            if (!endsWith(file, ".json")) {
                return;
            }
            byte[] bytes = readBounded(file, MAX_FILE_BYTES);
            if (bytes == null) {
                return;
            }
            String text = decode(bytes);
            try {
                collectComponentKeys(Json.parse(text), found);
            } catch (RuntimeException e) {
                // 解析失败退化为关键字匹配
                matchComponents(text.toLowerCase(Locale.ROOT), found);
            }
        });
        return found;
    }

    /** 扫描 scripts 下 JS 文件，收集命中的作弊关键词。 */
    private Set<String> scanScripts(Path packDir) {
        Set<String> matched = new LinkedHashSet<>();
        Path scripts = packDir.resolve("scripts");
        walkFiles(scripts, file -> {
            if (!endsWith(file, ".js")) {
                return;
            }
            byte[] bytes = readBounded(file, MAX_SCRIPT_BYTES);
            if (bytes == null) {
                return;
            }
            String text = decode(bytes).toLowerCase(Locale.ROOT);
            for (String keyword : CHEAT_SCRIPT_KEYWORDS) {
                if (text.contains(keyword)) {
                    matched.add(keyword);
                }
            }
        });
        return matched;
    }

    /** 资源包启发式：按包名、目录名与文件相对路径匹配 X-Ray / 全亮关键词。 */
    private Set<String> scanResourceHeuristics(Path packDir, String name) {
        Set<String> markers = new LinkedHashSet<>();
        if (name != null) {
            matchResourceKeywords(name.toLowerCase(Locale.ROOT), markers);
        }
        matchResourceKeywords(packDir.getFileName().toString().toLowerCase(Locale.ROOT), markers);
        walkFiles(packDir, file -> {
            String rel = packDir.relativize(file).toString()
                    .toLowerCase(Locale.ROOT).replace('\\', '/');
            matchResourceKeywords(rel, markers);
        });
        return markers;
    }

    private List<WorldBinding> scanWorlds(Path minecraftWorlds) {
        List<WorldBinding> out = new ArrayList<>();
        if (!Files.isDirectory(minecraftWorlds)) {
            return out;
        }
        List<Path> worlds = new ArrayList<>();
        try (Stream<Path> stream = Files.list(minecraftWorlds)) {
            stream.filter(Files::isDirectory).forEach(worlds::add);
        } catch (IOException | RuntimeException e) {
            return out;
        }
        worlds.sort(Comparator.comparing(p -> p.getFileName().toString(), String.CASE_INSENSITIVE_ORDER));
        for (Path world : worlds) {
            try {
                Path behaviorFile = world.resolve("world_behavior_packs.json");
                Path resourceFile = world.resolve("world_resource_packs.json");
                if (!Files.isRegularFile(behaviorFile) && !Files.isRegularFile(resourceFile)) {
                    continue;
                }
                out.add(new WorldBinding(
                        world.getFileName().toString(),
                        world.toAbsolutePath(),
                        readWorldPacks(behaviorFile),
                        readWorldPacks(resourceFile)));
            } catch (RuntimeException e) {
                // 单个坏世界跳过
            }
        }
        return out;
    }

    private Set<String> readWorldPacks(Path file) {
        Set<String> ids = new LinkedHashSet<>();
        byte[] bytes = readBounded(file, MAX_FILE_BYTES);
        if (bytes == null) {
            return ids;
        }
        Object root;
        try {
            root = Json.parse(decode(bytes));
        } catch (RuntimeException e) {
            return ids;
        }
        collectPackIds(root, ids);
        return ids;
    }

    private static void collectPackIds(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            Object id = map.get("pack_id");
            if (id == null) {
                id = map.get("packId");
            }
            if (id instanceof String s && !s.isBlank()) {
                out.add(s.trim().toLowerCase(Locale.ROOT));
            }
            for (Object value : map.values()) {
                collectPackIds(value, out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectPackIds(item, out);
            }
        }
    }

    private static void collectComponentKeys(Object node, Set<String> out) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getKey() instanceof String key) {
                    String lower = key.toLowerCase(Locale.ROOT);
                    if (SUSPICIOUS_COMPONENTS.contains(lower)) {
                        out.add(lower);
                    }
                }
                collectComponentKeys(entry.getValue(), out);
            }
        } else if (node instanceof List<?> list) {
            for (Object item : list) {
                collectComponentKeys(item, out);
            }
        }
    }

    private static void matchComponents(String lowerText, Set<String> out) {
        for (String component : SUSPICIOUS_COMPONENTS) {
            if (lowerText.contains(component)) {
                out.add(component);
            }
        }
    }

    private static void matchResourceKeywords(String text, Set<String> markers) {
        for (String keyword : XRAY_KEYWORDS) {
            if (text.contains(keyword)) {
                markers.add("xray");
                break;
            }
        }
        for (String keyword : FULLBRIGHT_KEYWORDS) {
            if (text.contains(keyword)) {
                markers.add("fullbright");
                break;
            }
        }
    }

    private static void walkFiles(Path dir, Consumer<Path> action) {
        if (!Files.isDirectory(dir)) {
            return;
        }
        int count = 0;
        try (Stream<Path> stream = Files.walk(dir)) {
            Iterator<Path> iterator = stream.iterator();
            while (iterator.hasNext() && count < MAX_FILES_PER_PACK) {
                Path file = iterator.next();
                count++;
                try {
                    if (Files.isRegularFile(file)) {
                        action.accept(file);
                    }
                } catch (RuntimeException e) {
                    // 单个文件出错不影响其余文件
                }
            }
        } catch (IOException | RuntimeException e) {
            // 目录遍历中断，返回已收集结果
        }
    }

    private static byte[] readBounded(Path file, long maxBytes) {
        try {
            if (!Files.isRegularFile(file)) {
                return null;
            }
            long size = Files.size(file);
            if (size < 0 || size > maxBytes) {
                return null;
            }
            return Files.readAllBytes(file);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static String decode(byte[] bytes) {
        int start = 0;
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            start = 3;
        }
        return new String(bytes, start, bytes.length - start, StandardCharsets.UTF_8);
    }

    private static String sha256Hex(byte[] data) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // JDK 必然带 SHA-256，走到这里说明运行环境异常
            return null;
        }
    }

    private static boolean endsWith(Path file, String suffix) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix);
    }

    private static String asString(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    private static String asVersion(Object value) {
        if (value instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (Object part : list) {
                if (part == null) {
                    continue;
                }
                if (sb.length() > 0) {
                    sb.append('.');
                }
                sb.append(part);
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return null;
    }
}
