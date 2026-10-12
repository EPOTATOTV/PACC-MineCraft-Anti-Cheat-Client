package com.potatotv.paccclient.detection.engine;

import com.potatotv.pacc.detection.local.os.FileEntry;
import com.potatotv.pacc.detection.local.os.HandleInfo;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.local.os.OsProbe;
import com.potatotv.pacc.detection.local.os.ProcessInfo;
import com.potatotv.pacc.detection.local.os.ThreadInfo;
import com.potatotv.pacc.detection.local.os.WindowInfo;
import com.potatotv.paccclient.probe.ModuleSnapshot;
import com.potatotv.paccclient.probe.ProcessSnapshot;
import com.potatotv.paccclient.probe.SystemProbe;

import java.util.ArrayList;
import java.util.List;

/**
 * 把端侧只读探针 {@link SystemProbe} 适配成引擎（PDE）的 {@link OsProbe} 契约。
 *
 * <p>数据边界（AGENTS.md 硬约束）：只透出本机进程 / 模块 / 窗口等元数据，不读进程内存内容；
 * 探针不支持或拿不到的能力返回空集合，由引擎检测器按「无数据 → 不产出」降级。
 * 当前映射能力：进程、模块（无 SHA-256，走名称通道）、窗口；线程 / 句柄 / 模块代码段 /
 * 游戏目录清单端侧探针未以原始列表暴露，一律返回空（不假装查得到）。
 */
public final class EngineOsProbe implements OsProbe {

    private final SystemProbe probe;

    public EngineOsProbe(SystemProbe probe) {
        this.probe = probe;
    }

    @Override
    public List<ProcessInfo> processes() {
        List<ProcessInfo> out = new ArrayList<>();
        for (ProcessSnapshot.ProcessInfo p : probe.snapshotProcesses().processes()) {
            out.add(new ProcessInfo(p.pid(), p.name(), p.path()));
        }
        return out;
    }

    @Override
    public List<ModuleInfo> modules(long pid) {
        String processName = processNameOf(pid);
        if (processName == null) {
            return List.of();
        }
        List<ModuleInfo> out = new ArrayList<>();
        for (ModuleSnapshot.ModuleInfo m : probe.snapshotModules(processName).modules()) {
            // 端侧探针不提供模块内容哈希，sha256 置空 → 引擎走文件名通道（低置信）
            out.add(new ModuleInfo(m.name(), m.path(), "", m.baseAddress(), m.size()));
        }
        return out;
    }

    @Override
    public List<ThreadInfo> threads(long pid) {
        // 原始线程列表未暴露（注入报告只给计数），不虚构 ThreadInfo
        return List.of();
    }

    @Override
    public List<WindowInfo> windows() {
        List<WindowInfo> out = new ArrayList<>();
        for (com.potatotv.paccclient.probe.WindowInfo w : probe.enumerateWindows()) {
            // 端侧窗口枚举不返回窗口类名，置空 → 引擎只走标题通道
            out.add(new WindowInfo(w.pid(), w.title(), ""));
        }
        return out;
    }

    @Override
    public List<HandleInfo> handlesTo(long pid) {
        // 句柄审计只给可疑句柄计数，不给持有者列表，避免误报来源进程
        return List.of();
    }

    @Override
    public byte[] readModuleCode(long pid, ModuleInfo module, int maxBytes) {
        // 端侧不做模块代码段读取（内核 / 原生能力边界），返回空数组
        return new byte[0];
    }

    @Override
    public List<FileEntry> listGameDirectory(String gameDir) {
        return List.of();
    }

    private String processNameOf(long pid) {
        for (ProcessSnapshot.ProcessInfo p : probe.snapshotProcesses().processes()) {
            if (p.pid() == pid) {
                return p.name();
            }
        }
        return null;
    }
}
