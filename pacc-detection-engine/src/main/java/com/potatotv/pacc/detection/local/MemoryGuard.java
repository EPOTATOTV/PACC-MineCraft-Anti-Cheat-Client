package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.HandleInfo;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.signature.FingerprintMatch;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * L3 内存层检测器（技术文档 &sect;6.3 内存篡改检测）。
 *
 * <p>a) 句柄审计：外部非系统进程持有游戏 PROCESS_ALL_ACCESS 句柄即内存工具特征；
 * b) 取游戏主模块（基址最低）代码段前 64 字节做内存特征码比对。
 * 游戏未运行时直接空；30 秒节流控制读取内存的开销（&sect;5.1 低负载）。
 */
public final class MemoryGuard implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(MemoryGuard.class.getName());

    private static final long THROTTLE_MS = 30_000;

    /** 主模块代码段读取字节数。 */
    private static final int CODE_READ_BYTES = 64;

    /** 完全访问句柄证据置信度。 */
    private static final double HANDLE_CONFIDENCE = 0.85;

    private long lastScanAt;

    @Override
    public String id() {
        return "memory-guard";
    }

    @Override
    public Layer layer() {
        return Layer.MEMORY;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            if (now - lastScanAt < THROTTLE_MS) {
                return out;
            }
            if (ctx.gamePid() <= 0 || !ctx.gameRunning()) {
                return out;
            }
            lastScanAt = now;
            auditHandles(ctx, out);
            checkMainModule(ctx, out);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "内存层扫描失败", e);
        }
        return out;
    }

    private void auditHandles(LocalScanContext ctx, List<Evidence> out) {
        Set<Long> reported = new HashSet<>();
        for (HandleInfo handle : ctx.probe().handlesTo(ctx.gamePid())) {
            if (!handle.fullAccess() || handle.sourcePid() == ctx.gamePid()) {
                continue;
            }
            if (LocalWhitelist.isSystemProcess(handle.sourceProcessName())) {
                continue;
            }
            // 同一来源进程只报一条
            if (!reported.add(handle.sourcePid())) {
                continue;
            }
            out.add(Evidence.of(id(), Layer.MEMORY, HANDLE_CONFIDENCE,
                    "外部进程持有游戏完全访问句柄（内存工具特征）: "
                            + handle.sourceProcessName() + "(pid=" + handle.sourcePid() + ")"));
        }
    }

    private void checkMainModule(LocalScanContext ctx, List<Evidence> out) {
        List<ModuleInfo> modules = ctx.probe().modules(ctx.gamePid());
        if (modules.isEmpty()) {
            return;
        }
        ModuleInfo main = modules.get(0);
        for (ModuleInfo module : modules) {
            if (module.baseAddress() < main.baseAddress()) {
                main = module;
            }
        }
        byte[] code = ctx.probe().readModuleCode(ctx.gamePid(), main, CODE_READ_BYTES);
        for (FingerprintMatch match : ctx.library().matchMemory(code)) {
            out.add(Evidence.of(id(), Layer.MEMORY, match.confidence(),
                    "游戏主模块内存特征命中: " + main.name() + " (" + match.fingerprint().name() + ")"));
        }
    }
}
