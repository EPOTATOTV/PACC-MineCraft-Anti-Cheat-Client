package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ProcessInfo;
import com.potatotv.pacc.detection.local.os.WindowInfo;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintMatch;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import java.util.ArrayList;
import java.util.List;

/**
 * L1 进程层检测器（技术文档 &sect;6.2 进程与窗口指纹）。
 *
 * <p>进程指纹只对 riskLevel &ge; 3 出证据；CE 类内存工具只在游戏运行时判定，
 * 避免误伤用户自用（文档 &sect;5.1）。窗口指纹类名命中 0.9、标题 0.85，
 * 置信度由 {@link SignatureLibrary#matchWindow} 按通道给出。
 */
public final class ProcessScanner implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(ProcessScanner.class.getName());

    /** 出证据的最低指纹风险等级。 */
    private static final int MIN_RISK_LEVEL = 3;

    @Override
    public String id() {
        return "process-scanner";
    }

    @Override
    public Layer layer() {
        return Layer.PROCESS;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            scanProcesses(ctx, out);
            scanWindows(ctx, out);
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "进程层扫描失败", e);
        }
        return out;
    }

    private void scanProcesses(LocalScanContext ctx, List<Evidence> out) {
        SignatureLibrary library = ctx.library();
        for (ProcessInfo proc : ctx.probe().processes()) {
            for (FingerprintMatch match : library.matchProcess(proc.name())) {
                if (match.fingerprint().riskLevel() < MIN_RISK_LEVEL) {
                    continue;
                }
                boolean memoryTool = match.fingerprint().category() == FingerprintCategory.MEMORY_TOOL;
                if (memoryTool && !ctx.gameRunning()) {
                    continue;
                }
                out.add(Evidence.of(id(), Layer.PROCESS, match.confidence(),
                        "命中作弊进程指纹: " + proc.name() + " (" + match.fingerprint().name() + ")"));
            }
        }
    }

    private void scanWindows(LocalScanContext ctx, List<Evidence> out) {
        for (WindowInfo win : ctx.probe().windows()) {
            for (FingerprintMatch match : ctx.library().matchWindow(win.windowClass(), win.title())) {
                out.add(Evidence.of(id(), Layer.PROCESS, match.confidence(),
                        "命中作弊窗口指纹: class=" + win.windowClass() + " title=" + win.title()
                                + " (" + match.fingerprint().name() + ")"));
            }
        }
    }
}
