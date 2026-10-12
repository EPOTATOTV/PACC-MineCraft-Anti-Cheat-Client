package com.potatotv.paccclient.detection.engine;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.EvidenceBatch;
import com.potatotv.pacc.detection.fusion.FusionEngine;
import com.potatotv.pacc.detection.fusion.Verdict;
import com.potatotv.pacc.detection.local.ApiHookChecker;
import com.potatotv.pacc.detection.local.GameDirGuard;
import com.potatotv.pacc.detection.local.InjectionTracer;
import com.potatotv.pacc.detection.local.InputAnalyzer;
import com.potatotv.pacc.detection.local.LocalDetector;
import com.potatotv.pacc.detection.local.LocalScanContext;
import com.potatotv.pacc.detection.local.LocalScanRunner;
import com.potatotv.pacc.detection.local.MemoryGuard;
import com.potatotv.pacc.detection.local.ModuleInspector;
import com.potatotv.pacc.detection.local.ProcessScanner;
import com.potatotv.pacc.detection.local.ThreadAnalyzer;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import com.potatotv.paccclient.detection.DetectionEvent;
import com.potatotv.paccclient.detection.PerfToggles;
import com.potatotv.paccclient.detection.samples.InputEvent;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;
import com.potatotv.paccclient.spi.DetectContext;
import com.potatotv.paccclient.spi.Detector;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * 检测引擎（PDE）玩家端六层检测桥（技术文档 §四 L1-L4 / §6 玩家端核心技术方案）。
 *
 * <p>把 {@code pacc-detection-engine} 的 8 个玩家端检测器（进程 / 模块 / 内存 / API Hook /
 * 线程 / 输入 / 游戏目录 / 注入追踪）跑在本机只读探针上，证据经六层融合（§8.1）出结论。
 * 与端侧既有 {@code ScannerRunner} 检测器并行，通道互补：
 * 既有检测器偏「进程名黑名单 + 签名」，本桥偏「指纹库 + 注入痕迹 + 输入分布熵」。
 *
 * <p>默认关闭（{@link PerfToggles#PDE_LOCAL}，与网络 / 屏幕层同档）——端侧已有更细的系统层扫描，
 * 本桥开启会叠加额外开销与重复命中，需管理端显式打开 {@code PACC_PDE_LOCAL_ENABLED=true}。
 */
public final class PdeLocalScanner implements Detector {

    private static final String ID = "pde_local_scanner";
    /** 与系统层扫描同档周期（引擎内部各检测器另有 30s 节流）。 */
    private static final long INTERVAL_MS = 30_000L;

    /** 视为游戏客户端的进程名（Java 版与基岩版）。 */
    private static final List<String> GAME_PROCESSES = List.of(
            "minecraft.windows.exe", "minecraft.exe", "javaw.exe", "java.exe");

    private final InputAnalyzer inputAnalyzer = new InputAnalyzer();
    private final LocalScanRunner runner;
    private final SignatureLibrary library = SignatureLibrary.builtin();
    private final FusionEngine fusion = new FusionEngine();

    public PdeLocalScanner() {
        List<LocalDetector> detectors = List.of(
                new ProcessScanner(),
                new ModuleInspector(),
                new MemoryGuard(),
                new ApiHookChecker(),
                new ThreadAnalyzer(),
                inputAnalyzer,
                new GameDirGuard(),
                new InjectionTracer());
        this.runner = new LocalScanRunner(detectors);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public long intervalMs() {
        return INTERVAL_MS;
    }

    @Override
    public Optional<DetectionEvent> detect(DetectContext ctx) {
        if (!PerfToggles.enabled(PerfToggles.PDE_LOCAL)) {
            return Optional.empty();
        }
        SystemProbe probe = ctx.systemProbe();
        if (!probe.isSupported(SystemProbe.Capability.PROCESSES)) {
            return Optional.empty();
        }

        // 点击事件喂给输入分布分析（§6.4：点击间隔熵 / GCD 倍数）
        ctx.inputSource().ifPresent(source -> source.inputEvents().stream()
                .filter(e -> e.kind() == InputEvent.Kind.CLICK)
                .forEach(e -> inputAnalyzer.recordClick(e.timestampMillis())));

        ProcessSnapshot.ProcessInfo game = findGameProcess(ctx);
        long gamePid = game == null ? -1L : game.pid();
        LocalScanContext scanContext = new LocalScanContext(
                ID, gamePid, null, new EngineOsProbe(probe), library, game != null);

        List<Evidence> evidence = runner.runAll(scanContext);
        ctx.putExtended("ext_pde_evidence_count", evidence.size());
        if (evidence.isEmpty()) {
            return Optional.empty();
        }

        EvidenceBatch batch = new EvidenceBatch(ID, evidence, false, false, Map.of());
        FusionEngine.FusionResult result = fusion.evaluateDetailed(batch);
        int score = (int) Math.round(result.score() * 100);
        ctx.putExtended("ext_pde_risk_score", score);
        // 并入系统层总分（同 DllSignatureScanner 口径：取较大值，避免互相覆盖）
        ctx.putExtended("ext_sys_score", Math.max(ctx.features().get("ext_sys_score"), score));

        if (result.verdict() == Verdict.CLEAN) {
            return Optional.empty();
        }
        Evidence top = evidence.stream()
                .max(Comparator.comparingDouble(Evidence::confidence))
                .orElseThrow();
        return Optional.of(new DetectionEvent(
                "pde_local_" + top.detectorId().toLowerCase(Locale.ROOT),
                severity(result.verdict()),
                score,
                top.detectorId(),
                null,
                "pde-local",
                ctx.osInfo().summary(),
                null));
    }

    private static ProcessSnapshot.ProcessInfo findGameProcess(DetectContext ctx) {
        for (ProcessSnapshot.ProcessInfo process : ctx.systemProbe().snapshotProcesses().processes()) {
            String name = process.name() == null ? "" : process.name().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && GAME_PROCESSES.contains(name)) {
                return process;
            }
        }
        return null;
    }

    private static String severity(Verdict verdict) {
        return verdict == Verdict.CONFIRMED ? "high" : "medium";
    }
}
