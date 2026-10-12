package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.local.os.FileEntry;
import com.potatotv.pacc.detection.local.os.HandleInfo;
import com.potatotv.pacc.detection.local.os.ModuleInfo;
import com.potatotv.pacc.detection.local.os.OsProbe;
import com.potatotv.pacc.detection.local.os.ProcessInfo;
import com.potatotv.pacc.detection.local.os.ThreadInfo;
import com.potatotv.pacc.detection.local.os.WindowInfo;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 可编程假探针：测试直接对字段赋值即可控制各枚举入口的返回。
 * {@code moduleCode} 以模块名（小写）为键，模拟 {@code readModuleCode}。
 */
final class FakeOsProbe implements OsProbe {

    List<ProcessInfo> processes = List.of();
    List<ModuleInfo> modules = List.of();
    List<ThreadInfo> threads = List.of();
    List<WindowInfo> windows = List.of();
    List<HandleInfo> handles = List.of();
    List<FileEntry> files = List.of();
    Map<String, byte[]> moduleCode = Map.of();

    @Override
    public List<ProcessInfo> processes() {
        return processes;
    }

    @Override
    public List<ModuleInfo> modules(long pid) {
        return modules;
    }

    @Override
    public List<ThreadInfo> threads(long pid) {
        return threads;
    }

    @Override
    public List<WindowInfo> windows() {
        return windows;
    }

    @Override
    public List<HandleInfo> handlesTo(long pid) {
        return handles;
    }

    @Override
    public byte[] readModuleCode(long pid, ModuleInfo module, int maxBytes) {
        String name = module.name() == null ? "" : module.name().toLowerCase(Locale.ROOT);
        byte[] code = moduleCode.get(name);
        if (code == null) {
            return new byte[0];
        }
        return code.length <= maxBytes ? code : Arrays.copyOf(code, maxBytes);
    }

    @Override
    public List<FileEntry> listGameDirectory(String gameDir) {
        return files;
    }
}
