package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.FingerprintMatch;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 模块层检测器（技术文档 &sect;6.2 注入模块审计）。
 *
 * <p>系统模块与 Windows 目录模块先走白名单（&sect;5.1），其余送指纹库：
 * SHA-256 哈希命中置信 1.0（内容不可伪造）、模块名命中 0.7，riskLevel &ge; 3 出证据。
 * 既不在白名单也不在指纹库的模块按"未知模块"降置信（0.3）上报，
 * 同一模块只报一次，避免每轮扫描刷屏。
 */
public final class ModuleInspector implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(ModuleInspector.class.getName());

    private static final int MIN_RISK_LEVEL = 3;

    /** 未知模块证据置信度（判定权重低，供融合层参考）。 */
    private static final double UNKNOWN_CONFIDENCE = 0.3;

    /** 已上报过的未知模块名（小写），同模块只报一次。 */
    private final Set<String> reportedUnknown = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "module-inspector";
    }

    @Override
    public Layer layer() {
        return Layer.MODULE;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            if (ctx.gamePid() <= 0) {
                return out;
            }
            for (ModuleInfo module : ctx.probe().modules(ctx.gamePid())) {
                if (LocalWhitelist.isSystemModule(module)) {
                    continue;
                }
                List<FingerprintMatch> matches = ctx.library().matchModule(module.name(), module.sha256());
                if (matches.isEmpty()) {
                    reportUnknownOnce(module, out);
                } else {
                    reportMatches(module, matches, out);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "模块层扫描失败", e);
        }
        return out;
    }

    private void reportMatches(ModuleInfo module, List<FingerprintMatch> matches, List<Evidence> out) {
        for (FingerprintMatch match : matches) {
            if (match.fingerprint().riskLevel() < MIN_RISK_LEVEL) {
                continue;
            }
            out.add(Evidence.of(id(), Layer.MODULE, match.confidence(),
                    "命中注入模块指纹: " + module.name() + " 通道=" + match.channel()
                            + " (" + match.fingerprint().name() + ")"));
        }
    }

    private void reportUnknownOnce(ModuleInfo module, List<Evidence> out) {
        String key = module.name() == null ? "" : module.name().toLowerCase(Locale.ROOT);
        if (key.isEmpty() || !reportedUnknown.add(key)) {
            return;
        }
        out.add(Evidence.of(id(), Layer.MODULE, UNKNOWN_CONFIDENCE,
                "未知模块，降置信度: " + module.name()));
    }
}
