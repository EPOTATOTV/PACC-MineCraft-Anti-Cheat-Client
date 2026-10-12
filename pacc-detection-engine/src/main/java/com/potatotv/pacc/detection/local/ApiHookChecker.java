package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ModuleInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * L2 API Hook 校验（技术文档 &sect;6.2 API Hook 检测）。
 *
 * <p>只校验游戏进程空间内的关键 API 模块（&sect;5.1：不触碰其他进程）。
 * 读取模块代码段前 16 字节，检查 inline hook 序言：首字节 E9（jmp rel32），
 * 或前 6 字节内出现 FF 25（jmp [addr] 间接跳转）。30 秒节流。
 */
public final class ApiHookChecker implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(ApiHookChecker.class.getName());

    private static final long THROTTLE_MS = 30_000;

    /** 模块代码段读取字节数（覆盖序言判定窗口）。 */
    private static final int CODE_READ_BYTES = 16;

    private static final double HOOK_CONFIDENCE = 0.75;

    /** 受检的关键 API 模块（小写）：输入、网络、计时。 */
    private static final Set<String> WATCH_MODULES = Set.of("user32.dll", "ws2_32.dll", "kernel32.dll");

    private long lastScanAt;

    @Override
    public String id() {
        return "api-hook-checker";
    }

    @Override
    public Layer layer() {
        return Layer.MODULE;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            long now = System.currentTimeMillis();
            if (now - lastScanAt < THROTTLE_MS) {
                return out;
            }
            if (ctx.gamePid() <= 0) {
                return out;
            }
            lastScanAt = now;
            for (ModuleInfo module : ctx.probe().modules(ctx.gamePid())) {
                String name = module.name() == null ? "" : module.name().toLowerCase(Locale.ROOT);
                if (!WATCH_MODULES.contains(name)) {
                    continue;
                }
                byte[] code = ctx.probe().readModuleCode(ctx.gamePid(), module, CODE_READ_BYTES);
                if (looksHooked(code)) {
                    out.add(Evidence.of(id(), Layer.MODULE, HOOK_CONFIDENCE,
                            "疑似 inline hook: " + module.name()));
                }
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "API Hook 校验失败", e);
        }
        return out;
    }

    /** 首字节 E9，或前 6 字节内出现 FF 25，即视为 hook 序言。 */
    private static boolean looksHooked(byte[] code) {
        if (code == null || code.length == 0) {
            return false;
        }
        if ((code[0] & 0xFF) == 0xE9) {
            return true;
        }
        for (int i = 0; i < 6 && i + 1 < code.length; i++) {
            if ((code[i] & 0xFF) == 0xFF && (code[i + 1] & 0xFF) == 0x25) {
                return true;
            }
        }
        return false;
    }
}
