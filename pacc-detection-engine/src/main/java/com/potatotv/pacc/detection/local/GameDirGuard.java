package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.FileEntry;
import com.potatotv.pacc.detection.signature.FingerprintMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L1 游戏目录完整性检测（技术文档 &sect;6.2 作弊资源特征 / &sect;2.2 客户端形态）。
 *
 * <p>只看文件名特征与指纹库模块名通道，不做内容全库哈希比对（内容哈希由
 * 指纹周更覆盖）。合法 Mod 文件先走白名单排除（&sect;5.1）；xray 类资源包按
 * 文档 &sect;5.1 属"仅提示不判定"，出 0.4 低置信证据。
 */
public final class GameDirGuard implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(GameDirGuard.class.getName());

    private static final double CONFIRMED_CONFIDENCE = 0.8;

    private static final double HINT_CONFIDENCE = 0.4;

    private static final int MIN_RISK_LEVEL = 3;

    /** 明确作弊客户端资源名（文件名小写子串命中即告警）。 */
    private static final List<String> CONFIRMED_NAMES =
            List.of("wurst", "liquidbounce", "meteor-client", "vape", "aristois");

    /** 仅提示资源名（xray 资源包可能被普通玩家当透视材质用，不直接判定）。 */
    private static final List<String> HINT_NAMES = List.of("xray", "x-ray");

    /** 已上报的相对路径（小写），同文件只报一次。 */
    private final Set<String> reportedPaths = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "game-dir-guard";
    }

    @Override
    public Layer layer() {
        return Layer.PROCESS;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            if (ctx.gameDir() == null || ctx.gameDir().isBlank()) {
                return out;
            }
            for (FileEntry entry : ctx.probe().listGameDirectory(ctx.gameDir())) {
                inspect(ctx, entry, out);
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "游戏目录扫描失败", e);
        }
        return out;
    }

    private void inspect(LocalScanContext ctx, FileEntry entry, List<Evidence> out) {
        String file = fileName(entry.relativePath());
        if (file.isEmpty() || LocalWhitelist.isLegitMod(file)) {
            return;
        }
        String dedupeKey = entry.relativePath() == null ? "" : entry.relativePath().toLowerCase(Locale.ROOT);
        if (!reportedPaths.add(dedupeKey)) {
            return;
        }
        for (String confirmed : CONFIRMED_NAMES) {
            if (file.contains(confirmed)) {
                out.add(Evidence.of(id(), Layer.PROCESS, CONFIRMED_CONFIDENCE,
                        "游戏目录存在作弊客户端资源: " + entry.relativePath()));
                return;
            }
        }
        for (String hint : HINT_NAMES) {
            if (file.contains(hint)) {
                out.add(Evidence.of(id(), Layer.PROCESS, HINT_CONFIDENCE,
                        "游戏目录存在透视类资源（仅提示，不判定）: " + entry.relativePath()));
                return;
            }
        }
        reportModuleFingerprint(ctx, file, entry, out);
    }

    /** 文件名走指纹库模块名通道（0.7 置信，riskLevel &ge; 3 才出）。 */
    private void reportModuleFingerprint(LocalScanContext ctx, String file, FileEntry entry, List<Evidence> out) {
        for (FingerprintMatch match : ctx.library().matchModule(file, "")) {
            if (match.fingerprint().riskLevel() < MIN_RISK_LEVEL) {
                continue;
            }
            out.add(Evidence.of(id(), Layer.PROCESS, match.confidence(),
                    "游戏目录文件命中作弊指纹: " + entry.relativePath()
                            + " (" + match.fingerprint().name() + ")"));
            return;
        }
    }

    /** 取相对路径的最后一段文件名（小写）。 */
    private static String fileName(String relativePath) {
        if (relativePath == null) {
            return "";
        }
        String normalized = relativePath.replace('\\', '/').toLowerCase(Locale.ROOT);
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }
}
