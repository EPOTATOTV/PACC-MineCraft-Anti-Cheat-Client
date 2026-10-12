package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 注入痕迹检测（技术文档 &sect;6.2 注入痕迹）。
 *
 * <p>两条启发式：a) 模块路径异常——路径为空、不含目录结构、或落在 Temp 目录
 * （注入器常把 DLL 释放到临时目录后手工映射）；b) 模块映像大小为 0。
 * 系统模块先走白名单排除，同模块同规则只报一次。
 */
public final class InjectionTracer implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(InjectionTracer.class.getName());

    private static final double PATH_ANOMALY_CONFIDENCE = 0.8;

    private static final double ZERO_SIZE_CONFIDENCE = 0.5;

    /** 已上报的 "模块名|规则" 键。 */
    private final Set<String> reportedKeys = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "injection-tracer";
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
                String path = module.path() == null ? "" : module.path().toLowerCase(Locale.ROOT);
                boolean pathAnomaly = path.isBlank()
                        || !(path.contains("\\") || path.contains("/"))
                        || path.contains("\\temp\\")
                        || path.contains("\\appdata\\local\\temp\\");
                if (pathAnomaly) {
                    report(module, "path", PATH_ANOMALY_CONFIDENCE,
                            "模块路径异常（疑似注入落地）: " + module.name(), out);
                }
                if (module.size() <= 0) {
                    report(module, "size", ZERO_SIZE_CONFIDENCE,
                            "模块映像大小为 0（疑似手工映射注入）: " + module.name(), out);
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "注入痕迹扫描失败", e);
        }
        return out;
    }

    private void report(ModuleInfo module, String rule, double confidence, String detail, List<Evidence> out) {
        String name = module.name() == null ? "" : module.name().toLowerCase(Locale.ROOT);
        if (name.isEmpty() || !reportedKeys.add(name + "|" + rule)) {
            return;
        }
        out.add(Evidence.of(id(), Layer.MODULE, confidence, detail));
    }
}
