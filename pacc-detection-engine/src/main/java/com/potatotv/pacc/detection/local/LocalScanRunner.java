package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;

import java.util.ArrayList;
import java.util.List;

/**
 * 玩家端本地扫描调度器（技术文档 &sect;4.2 local 包）。
 *
 * <p>按检测器列表逐个执行 {@link LocalDetector#scan}，聚合证据；单个检测器抛出
 * 运行时异常时以 WARNING 记录并跳过，不影响其余检测器（契约：扫描失败不得中断整体）。
 */
public final class LocalScanRunner {

    private final List<LocalDetector> detectors;

    public LocalScanRunner(List<LocalDetector> detectors) {
        this.detectors = detectors == null ? List.of() : List.copyOf(detectors);
    }

    /** 执行一轮全量本地扫描，返回聚合证据。 */
    public List<Evidence> runAll(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        for (LocalDetector detector : detectors) {
            try {
                List<Evidence> produced = detector.scan(ctx);
                if (produced != null) {
                    out.addAll(produced);
                }
            } catch (RuntimeException e) {
                System.getLogger(detector.id()).log(
                        System.Logger.Level.WARNING, "检测器扫描异常，已跳过: " + detector.id(), e);
            }
        }
        return out;
    }

    /** 默认检测器组：L1 进程 / 目录，L2 模块 / Hook / 线程 / 注入，L3 内存，L4 输入。 */
    public static List<LocalDetector> defaultDetectors() {
        return List.of(
                new ProcessScanner(),
                new GameDirGuard(),
                new ModuleInspector(),
                new ApiHookChecker(),
                new ThreadAnalyzer(),
                new InjectionTracer(),
                new MemoryGuard(),
                new InputAnalyzer());
    }
}
