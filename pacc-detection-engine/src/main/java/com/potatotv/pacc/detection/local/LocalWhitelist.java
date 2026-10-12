package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.local.os.ModuleInfo;

import java.util.Locale;
import java.util.Set;

/**
 * 本地检测白名单（技术文档 &sect;5.1 误报抑制列）。
 *
 * <p>系统 DLL 与 Windows 目录下的模块不参与指纹比对，避免把系统自身模块当作弊；
 * 已登记的合法 Mod 不触发目录资源告警；系统进程持有游戏句柄属正常行为，句柄审计时豁免。
 * 白名单只做减法（排除），方向是宁可漏报、不可误报。
 */
public final class LocalWhitelist {

    /** 常见系统模块裸名（小写，不含扩展名）。 */
    private static final Set<String> SYSTEM_MODULES = Set.of(
            "ntdll", "kernel32", "kernelbase", "user32", "gdi32", "gdiplus",
            "d3d9", "d3d11", "dxgi", "mscoree", "clr", "clrjit", "wintrust",
            "advapi32", "ws2_32", "wsock32", "shell32", "ole32", "oleaut32",
            "comctl32", "comdlg32", "shlwapi", "version", "winspool", "uxtheme",
            "riched20", "riched32", "dbghelp", "psapi", "iphlpapi", "dnsapi",
            "winmm", "opengl32", "glu32", "sechost", "crypt32", "ncrypt",
            "msvcp140", "vcruntime140", "ucrtbase", "jawt", "jvm", "java");

    /** Windows 系统目录路径前缀（小写）。 */
    private static final String WINDOWS_PREFIX = "c:\\windows\\";

    /** 允许持有游戏进程句柄的系统进程裸名（小写）。 */
    private static final Set<String> SYSTEM_PROCESSES = Set.of(
            "system", "idle", "smss", "csrss", "wininit", "winlogon", "services",
            "lsass", "svchost", "explorer", "dwm", "conhost", "runtimebroker",
            "searchindexer", "audiodg", "wmiprvse", "taskhostw", "fontdrvhost",
            "sihost", "ctfmon", "msmpeng", "securityhealthservice", "perfmon");

    /** 已登记合法 Mod 名（小写子串，覆盖主流性能 / 图形 / 库类 Mod）。 */
    private static final Set<String> LEGIT_MODS = Set.of(
            "optifine", "sodium", "lithium", "phosphor", "starlight", "fabric-api",
            "fabricloader", "fabric", "forge", "neoforge", "minecraftforge",
            "minecraft", "jei", "jade", "hwyla", "waila", "modmenu", "cloth-config",
            "architectury", "balm", "iris", "oculus", "embeddium", "rubidium",
            "indium", "ferrite-core", "krypton", "lazydfu", "appleskin",
            "journeymap", "xaero", "litematica", "create", "botania", "distanthorizons");

    private LocalWhitelist() {
    }

    /** 系统模块或位于 Windows 目录：跳过指纹比对。 */
    public static boolean isSystemModule(ModuleInfo module) {
        if (module == null) {
            return false;
        }
        String path = module.path() == null ? "" : module.path().toLowerCase(Locale.ROOT);
        if (path.startsWith(WINDOWS_PREFIX)) {
            return true;
        }
        return SYSTEM_MODULES.contains(bareName(module.name()));
    }

    /** 系统来源进程持有游戏句柄属正常（句柄审计豁免）。 */
    public static boolean isSystemProcess(String processName) {
        return SYSTEM_PROCESSES.contains(bareName(processName));
    }

    /**
     * 已登记合法 Mod。文件名小写后包含任一登记名即视为合法；
     * 白名单方向宁松勿紧（例如 {@code sodium-0.5.3.jar} 命中 {@code sodium}）。
     */
    public static boolean isLegitMod(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String mod : LEGIT_MODS) {
            if (lower.contains(mod)) {
                return true;
            }
        }
        return false;
    }

    /** 取文件名裸名：小写、去最后一个扩展名。 */
    private static String bareName(String fileName) {
        if (fileName == null) {
            return "";
        }
        String lower = fileName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        return dot > 0 ? lower.substring(0, dot) : lower;
    }
}
