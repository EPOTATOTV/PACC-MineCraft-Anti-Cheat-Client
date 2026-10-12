package com.potatotv.pacc.detection.signature;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 指纹库门面：聚合五类签名库并提供统一匹配入口（技术文档 §4.2 signature 包）。
 *
 * <p>内置指纹随引擎分发（{@link #builtin()}），社区上报与每周更新通过
 * {@link #register} 运行时追加。线程安全：注册与匹配可并发。
 *
 * <p>通道置信度（文件名可伪造、内容哈希不可伪造，文档 §九 反规避）：
 * 哈希命中 1.0 &gt; 内存特征 0.95 &gt; 窗口类名 0.9 &gt; 进程名 0.8 &gt; 模块名 0.7 &gt; 配置特征 0.6。
 */
public final class SignatureLibrary {

    private final List<CheatFingerprint> fingerprints = new CopyOnWriteArrayList<>();

    private SignatureLibrary(List<CheatFingerprint> initial) {
        fingerprints.addAll(initial);
    }

    /** 加载内置指纹库。 */
    public static SignatureLibrary builtin() {
        return new SignatureLibrary(BuiltinFingerprints.all());
    }

    /** 用给定指纹集合构造（测试与热更新用）。 */
    public static SignatureLibrary of(List<CheatFingerprint> fingerprints) {
        return new SignatureLibrary(fingerprints == null ? List.of() : fingerprints);
    }

    /** 运行时追加指纹（社区上报 / 每周更新）。 */
    public void register(CheatFingerprint fingerprint) {
        if (fingerprint != null) {
            fingerprints.add(fingerprint);
        }
    }

    /** 批量追加。 */
    public void registerAll(List<CheatFingerprint> fingerprints) {
        if (fingerprints != null) {
            this.fingerprints.addAll(fingerprints);
        }
    }

    /** 当前指纹总数。 */
    public int size() {
        return fingerprints.size();
    }

    /** 指纹只读视图。 */
    public List<CheatFingerprint> fingerprints() {
        return List.copyOf(fingerprints);
    }

    /** 匹配进程名（L1）。 */
    public List<FingerprintMatch> matchProcess(String processName) {
        return matchText(processName, CheatFingerprint::processNames, FingerprintType.PROCESS, 0.8);
    }

    /** 匹配窗口（类名精确匹配 0.9，标题子串 0.85，取两者最高）。 */
    public List<FingerprintMatch> matchWindow(String windowClass, String windowTitle) {
        List<FingerprintMatch> hits = new ArrayList<>();
        String cls = lower(windowClass);
        String title = lower(windowTitle);
        for (CheatFingerprint fp : fingerprints) {
            double best = 0;
            String value = "";
            if (!cls.isBlank()) {
                for (String c : fp.windowClasses()) {
                    if (cls.equals(lower(c))) {
                        if (best < 0.9) {
                            best = 0.9;
                            value = c;
                        }
                    }
                }
            }
            if (!title.isBlank()) {
                for (String t : fp.windowTitles()) {
                    String lt = lower(t);
                    if (!lt.isBlank() && title.contains(lt) && best < 0.85) {
                        best = 0.85;
                        value = t;
                    }
                }
            }
            if (best > 0) {
                hits.add(new FingerprintMatch(fp, FingerprintType.WINDOW, value, best));
            }
        }
        return hits;
    }

    /** 匹配注入模块：文件名模式 0.7，SHA-256 命中 1.0（内容哈希不可伪造）。 */
    public List<FingerprintMatch> matchModule(String moduleName, String sha256) {
        List<FingerprintMatch> hits = new ArrayList<>();
        String name = lower(moduleName);
        String hash = sha256 == null ? "" : sha256.toLowerCase(Locale.ROOT);
        for (CheatFingerprint fp : fingerprints) {
            FingerprintMatch best = null;
            for (String h : fp.fileHashes()) {
                if (!hash.isBlank() && hash.equalsIgnoreCase(h)) {
                    best = new FingerprintMatch(fp, FingerprintType.MODULE, h, 1.0);
                    break;
                }
            }
            if (best == null && !name.isBlank()) {
                for (String m : fp.moduleNames()) {
                    if (name.equals(lower(m)) || name.endsWith(lower(m))) {
                        best = new FingerprintMatch(fp, FingerprintType.MODULE, m, 0.7);
                        break;
                    }
                }
            }
            if (best != null) {
                hits.add(best);
            }
        }
        return hits;
    }

    /** 匹配内存特征码（{@code ??} 通配字节模式）。 */
    public List<FingerprintMatch> matchMemory(byte[] memoryDump) {
        if (memoryDump == null || memoryDump.length == 0) {
            return List.of();
        }
        List<FingerprintMatch> hits = new ArrayList<>();
        for (CheatFingerprint fp : fingerprints) {
            for (String pattern : fp.memoryPatterns()) {
                if (MemoryPatternMatcher.contains(memoryDump, pattern)) {
                    hits.add(new FingerprintMatch(fp, FingerprintType.MEMORY, pattern, 0.95));
                    break;
                }
            }
        }
        return hits;
    }

    /** 匹配作弊配置 / 脚本内容特征（AutoConfig 检测，文档 §2.2）。 */
    public List<FingerprintMatch> matchConfig(String content) {
        String text = lower(content);
        if (text.isBlank()) {
            return List.of();
        }
        List<FingerprintMatch> hits = new ArrayList<>();
        for (CheatFingerprint fp : fingerprints) {
            if (fp.type() != FingerprintType.CONFIG) {
                continue;
            }
            for (String marker : fp.windowTitles()) {
                String lm = lower(marker);
                if (!lm.isBlank() && text.contains(lm)) {
                    hits.add(new FingerprintMatch(fp, FingerprintType.CONFIG, marker, 0.6));
                    break;
                }
            }
        }
        return hits;
    }

    private List<FingerprintMatch> matchText(
            String text, java.util.function.Function<CheatFingerprint, List<String>> patterns,
            FingerprintType channel, double confidence) {
        String value = lower(text);
        if (value.isBlank()) {
            return List.of();
        }
        List<FingerprintMatch> hits = new ArrayList<>();
        for (CheatFingerprint fp : fingerprints) {
            for (String pattern : patterns.apply(fp)) {
                String p = lower(pattern);
                if (!p.isBlank() && value.contains(p)) {
                    hits.add(new FingerprintMatch(fp, channel, pattern, confidence));
                    break;
                }
            }
        }
        return hits;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
