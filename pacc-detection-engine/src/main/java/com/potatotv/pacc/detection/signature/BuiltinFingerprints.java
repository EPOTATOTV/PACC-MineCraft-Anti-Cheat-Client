package com.potatotv.pacc.detection.signature;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置指纹库（技术文档 §十一 M1 里程碑：指纹库 ≥100 条，覆盖四大类别）。
 *
 * <p>指纹来源（文档 §6.1）：官方发布 JAR/SHA256、公开仓库样本、社区上报、作弊论坛签名爬取。
 * 本类只存特征、不存样本（版权合规，文档 §12）。特征以进程名 / 窗口 / 模块名 / 配置标记为主，
 * fileHashes 在拿到真实样本哈希前一律留空，不编造哈希，缺口由每周更新与
 * threatintel 包的社区上报通道补充。
 *
 * <p>CONFIG 条目的标记串按既有契约放进 windowTitles 字段：{@code SignatureLibrary.matchConfig}
 * 只读该字段（文档 §2.2 AutoConfig 检测）。
 */
final class BuiltinFingerprints {

    private BuiltinFingerprints() {
    }

    static List<CheatFingerprint> all() {
        List<CheatFingerprint> all = new ArrayList<>();
        all.addAll(clients());
        all.addAll(memoryTools());
        all.addAll(injectors());
        all.addAll(ghosts());
        all.addAll(drivers());
        all.addAll(configs());
        return List.copyOf(all);
    }

    /**
     * 模块化作弊客户端。本体跑在 Minecraft 进程里，进程通道基本只覆盖启动器；
     * JAR 模块名可伪造，带版本号的产物名靠每周更新补条目。
     */
    private static List<CheatFingerprint> clients() {
        List<CheatFingerprint> list = new ArrayList<>();
        // P-10：2026 年出现的 ML 转头 / 客户端绕过家族。产物名与内存串都收，
        // 单靠 JAR 名会被改名绕过，内存串是第二道。
        list.add(mod("nanokalite-client", "NanokaLite（ML 转头）", FingerprintCategory.CLIENT, 4,
                List.of("nanokalite.jar", "nanokalite-client.jar", "nanoka-ml.jar")));
        list.add(mem("nanokalite-memory", "NanokaLite 内存字符串特征", FingerprintCategory.CLIENT, 4,
                List.of("6E 61 6E 6F 6B 61 6C 69 74 65")));
        list.add(mod("opai-client", "OPAI 客户端", FingerprintCategory.CLIENT, 4,
                List.of("opai.jar", "opai-client.jar", "opai-loader.jar")));
        list.add(mem("opai-memory", "OPAI 内存字符串特征", FingerprintCategory.CLIENT, 4,
                List.of("6F 70 61 69 2D 63 6C 69 65 6E 74")));
        // Wurst 7.x：JAR 模块名 + 启动器进程 + 驻留内存的 ASCII 客户端名。
        list.add(mod("wurst-client", "Wurst Client 7.x", FingerprintCategory.CLIENT, 4,
                List.of("wurst-client.jar", "wurst_client.jar", "wurst.jar")));
        list.add(mod("wurst-legacy", "Wurst 2.x/6.x", FingerprintCategory.CLIENT, 4,
                List.of("wurst-2.jar", "wurst-6.jar", "wurst_legacy.jar")));
        list.add(mod("wurst-plus-client", "Wurst+2/3（Wurst 私改分支）", FingerprintCategory.CLIENT, 4,
                List.of("wurst-plus-two.jar", "wurst-plus-three.jar", "wurst+2.jar")));
        list.add(proc("wurst-installer", "Wurst 安装器", FingerprintCategory.CLIENT, 4,
                List.of("wurst installer", "wurst-installer")));
        // 客户端字符串会驻留堆内存，ASCII 字节序是低成本的启发式特征。
        list.add(mem("wurst-memory", "Wurst 内存字符串特征", FingerprintCategory.CLIENT, 4,
                List.of("77 75 72 73 74 2D 63 6C 69 65 6E 74")));
        list.add(mod("liquidbounce-client", "LiquidBounce", FingerprintCategory.CLIENT, 4,
                List.of("liquidbounce.jar", "liquidbounce-build.jar", "liquidbounce-nextgen.jar")));
        list.add(mod("liquidbounce-legacy", "LiquidBounce Legacy 分支", FingerprintCategory.CLIENT, 4,
                List.of("liquidbounce-legacy.jar", "liquidbounce-b72.jar")));
        list.add(win("liquidbounce-window", "LiquidBounce ClickGUI 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("LiquidBounce ClickGUI", "LiquidBounce")));
        list.add(proc("liquidbounce-installer", "LiquidBounce 启动器", FingerprintCategory.CLIENT, 4,
                List.of("liquidbounce installer", "liquidbounce-launcher")));
        list.add(mem("liquidbounce-memory", "LiquidBounce 内存字符串特征", FingerprintCategory.CLIENT, 4,
                List.of("6C 69 71 75 69 64 62 6F 75 6E 63 65")));
        list.add(mod("meteor-client", "Meteor Client", FingerprintCategory.CLIENT, 4,
                List.of("meteor-client.jar", "meteor_client.jar")));
        list.add(win("meteor-window", "Meteor Client GUI 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Meteor Client", "Meteor GUI")));
        list.add(mod("meteor-rejects-addon", "Meteor Rejects（Meteor 附加模块）", FingerprintCategory.CLIENT, 4,
                List.of("meteor-rejects.jar", "meteor-rejects-addon.jar")));
        list.add(mem("meteor-memory", "Meteor 内存字符串特征", FingerprintCategory.CLIENT, 4,
                List.of("6D 65 74 65 6F 72 2D 63 6C 69 65 6E 74")));
        // Baritone 本体是寻路工具，但与 Meteor/Impact 组合即成自动作弊，按组合件收进库。
        list.add(mod("baritone-bot", "Baritone 自动寻路", FingerprintCategory.CLIENT, 3,
                List.of("baritone-standalone-forge.jar", "baritone-standalone-fabric.jar",
                        "baritone-api-forge.jar", "baritone-api-fabric.jar")));
        list.add(mod("impact-client", "Impact Client", FingerprintCategory.CLIENT, 4,
                List.of("impact-4.9.jar", "impact-4.10.jar", "impact-4.11.jar", "impact-4.12.jar")));
        list.add(proc("impact-installer", "Impact 安装器", FingerprintCategory.CLIENT, 4,
                List.of("impact installer", "impactinstaller")));
        list.add(win("impact-window", "Impact Client 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Impact Client")));
        list.add(mod("aristois-client", "Aristois", FingerprintCategory.CLIENT, 4,
                List.of("aristois.jar", "aristois-client.jar")));
        list.add(proc("aristois-installer", "Aristois 安装器", FingerprintCategory.CLIENT, 4,
                List.of("aristois installer", "aristois-launcher")));
        list.add(mod("flux-b13", "Flux b13", FingerprintCategory.CLIENT, 4,
                List.of("flux b13.jar", "flux-b13.jar")));
        list.add(mod("flux-b17", "Flux b17", FingerprintCategory.CLIENT, 4,
                List.of("flux b17.jar", "flux-b17.jar")));
        list.add(win("flux-window", "Flux ClickGUI 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Flux b13", "Flux b17")));
        list.add(mod("sigma-5", "Sigma 5.0", FingerprintCategory.CLIENT, 4,
                List.of("sigma-5.0.jar", "sigma 5.0.jar", "sigma5.jar")));
        list.add(win("sigma-window", "Sigma / Jello 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Sigma 5.0", "Jello for Sigma")));
        list.add(mod("salhack-client", "Salhack", FingerprintCategory.CLIENT, 4,
                List.of("salhack.jar", "salhack-client.jar")));
        list.add(mod("future-client", "Future Client", FingerprintCategory.CLIENT, 4,
                List.of("future-client.jar", "future.jar")));
        list.add(proc("future-installer", "Future 安装器", FingerprintCategory.CLIENT, 4,
                List.of("future installer", "future-client installer")));
        list.add(mod("rusherhack-client", "RusherHack", FingerprintCategory.CLIENT, 4,
                List.of("rusherhack.jar", "rusherhack-client.jar", "rusherhack-beta.jar")));
        list.add(win("rusherhack-window", "RusherHack GUI 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("RusherHack")));
        list.add(mod("quantum-client", "Quantum Client", FingerprintCategory.CLIENT, 4,
                List.of("quantum-client.jar", "quantum.jar")));
        list.add(win("quantum-window", "Quantum Client 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Quantum Client")));
        list.add(mod("novoline-client", "Novoline", FingerprintCategory.CLIENT, 4,
                List.of("novoline.jar", "novoline-client.jar")));
        list.add(win("novoline-window", "Novoline 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("Novoline")));
        list.add(mod("bleachhack-client", "BleachHack", FingerprintCategory.CLIENT, 4,
                List.of("bleachhack.jar", "bleachhack-fabric.jar", "bleachhack-forge.jar")));
        list.add(win("bleachhack-window", "BleachHack 窗口", FingerprintCategory.CLIENT, 4,
                List.of(), List.of("BleachHack")));
        // Mod 内的 ClickGUI 多为游戏内渲染，此条主要抓外挂面板弹出的独立 OS 窗口。
        list.add(win("generic-clickgui", "Hacked Client 通用 ClickGUI 面板", FingerprintCategory.CLIENT, 3,
                List.of("ClickGUI"), List.of("ClickGUI")));
        list.add(mod("lambda-client", "Lambda（开源客户端）", FingerprintCategory.CLIENT, 3,
                List.of("lambda-client.jar", "lambda.jar")));
        list.add(mod("pyro-client", "Pyro Client", FingerprintCategory.CLIENT, 4,
                List.of("pyro-client.jar", "pyroclient.jar")));
        list.add(mod("thunderhack-client", "ThunderHack", FingerprintCategory.CLIENT, 4,
                List.of("thunderhack.jar", "thunderhack-recode.jar", "thunder-hack.jar")));
        list.add(mod("ares-client", "Ares", FingerprintCategory.CLIENT, 3,
                List.of("ares.jar", "ares-client.jar")));
        list.add(mod("forgehax-client", "ForgeHax", FingerprintCategory.CLIENT, 3,
                List.of("forgehax.jar", "forgehax-fabric.jar")));
        list.add(mod("wolfram-client", "Wolfram Client", FingerprintCategory.CLIENT, 4,
                List.of("wolfram-client.jar", "wolfram.jar")));
        list.add(mod("huzuni-client", "Huzuni", FingerprintCategory.CLIENT, 4,
                List.of("huzuni.jar", "huzuni-3.5.jar")));
        return list;
    }

    /** 内存修改工具。CE 系误报由白名单层兜底，训练器类按品牌 + 通用启发式双路收口。 */
    private static List<CheatFingerprint> memoryTools() {
        List<CheatFingerprint> list = new ArrayList<>();
        // cheatengine 覆盖 x86/x86_64/AVX2 等全部发行变体的进程名。
        list.add(proc("cheat-engine", "Cheat Engine", FingerprintCategory.MEMORY_TOOL, 5,
                List.of("cheatengine")));
        list.add(win("cheat-engine-window", "Cheat Engine 主窗口", FingerprintCategory.MEMORY_TOOL, 5,
                List.of("TFormCheatEngine"), List.of("Cheat Engine")));
        // CE 是 Delphi 编译的，条目用 Delphi 常见函数序言做启发式；具体版本特征码走每周更新。
        list.add(mem("cheat-engine-memory", "Cheat Engine 内存特征", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("55 8B EC 6A FF 68 ?? ?? ?? ?? 64 A1 00 00 00 00", "55 8B EC 83 C4 ?? 53 56 57")));
        list.add(proc("artmoney", "ArtMoney", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("artmoney")));
        list.add(win("artmoney-window", "ArtMoney 主窗口", FingerprintCategory.MEMORY_TOOL, 4,
                List.of(), List.of("ArtMoney")));
        list.add(proc("wemod", "WeMod", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("wemod", "wemodauxiliaryservice")));
        list.add(win("wemod-window", "WeMod 窗口", FingerprintCategory.MEMORY_TOOL, 3,
                List.of(), List.of("WeMod")));
        // "fling" 已在 ptv-client 现有黑名单，这里补齐训练器窗口与品牌变体。
        list.add(proc("fling-trainer", "FLiNG Trainer", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("flingtrainer", "fling trainer")));
        list.add(win("fling-trainer-window", "FLiNG Trainer 窗口", FingerprintCategory.MEMORY_TOOL, 4,
                List.of(), List.of("FLiNG")));
        list.add(proc("mrantifun-trainer", "MrAntiFun Trainer", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("mrantifun")));
        list.add(proc("cheat-happens-trainer", "Cheat Happens Trainer", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("cheathappens", "cheat happens")));
        list.add(proc("generic-trainer", "通用训练器启发式", FingerprintCategory.MEMORY_TOOL, 3,
                List.of("trainer")));
        list.add(win("trainer-window", "通用训练器窗口", FingerprintCategory.MEMORY_TOOL, 3,
                List.of(), List.of("Trainer +")));
        list.add(proc("tsearch", "TSearch", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("tsearch")));
        list.add(proc("mhs", "Memory Hacking Software", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("memory hacking software", "mhs.exe")));
        list.add(proc("squalr", "Squalr", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("squalr")));
        list.add(proc("crysearch", "CrySearch", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("crysearch")));
        list.add(proc("pince", "PINCE", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("pince")));
        list.add(proc("gameconqueror", "GameConqueror / scanmem", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("gameconqueror", "scanmem")));
        list.add(proc("cheatomatic", "Cheat-O-Matic", FingerprintCategory.MEMORY_TOOL, 3,
                List.of("cheat-o-matic", "cheatomatic")));
        list.add(proc("megadev-trainer", "MegaDev Trainer", FingerprintCategory.MEMORY_TOOL, 4,
                List.of("megadev")));
        return list;
    }

    /** 注入器。Process Hacker / System Informer / Syringe 属双用途工具，风险等级压低。 */
    private static List<CheatFingerprint> injectors() {
        List<CheatFingerprint> list = new ArrayList<>();
        list.add(proc("xenos-injector", "Xenos Injector", FingerprintCategory.INJECTOR, 4,
                List.of("xenos")));
        list.add(win("xenos-window", "Xenos Injector 窗口", FingerprintCategory.INJECTOR, 4,
                List.of(), List.of("Xenos Injector")));
        list.add(proc("extreme-injector", "Extreme Injector v3", FingerprintCategory.INJECTOR, 4,
                List.of("extreme injector", "extremeinjector")));
        list.add(win("extreme-injector-window", "Extreme Injector 窗口", FingerprintCategory.INJECTOR, 4,
                List.of(), List.of("Extreme Injector")));
        list.add(proc("gh-injector", "Guided Hacking Injector", FingerprintCategory.INJECTOR, 4,
                List.of("guidedhacking", "guided hacking", "gh injector")));
        list.add(proc("process-hacker", "Process Hacker", FingerprintCategory.INJECTOR, 2,
                List.of("processhacker", "process hacker")));
        list.add(win("process-hacker-window", "Process Hacker 主窗口", FingerprintCategory.INJECTOR, 2,
                List.of("ProcessHacker"), List.of("Process Hacker")));
        list.add(proc("system-informer", "System Informer", FingerprintCategory.INJECTOR, 2,
                List.of("systeminformer", "system informer")));
        list.add(proc("remotedll", "RemoteDLL", FingerprintCategory.INJECTOR, 4,
                List.of("remotedll")));
        list.add(proc("dll-injector-generic", "通用 DLL 注入器", FingerprintCategory.INJECTOR, 4,
                List.of("dll injector", "dllinjector")));
        list.add(proc("manual-map-injector", "Manual Map Injector", FingerprintCategory.INJECTOR, 4,
                List.of("manual map injector", "manualmap")));
        list.add(proc("apoc-injector", "Apoc Injector", FingerprintCategory.INJECTOR, 4,
                List.of("apoc injector")));
        list.add(proc("blackbone", "BlackBone", FingerprintCategory.INJECTOR, 4,
                List.of("blackbone")));
        list.add(proc("syringe-injector", "Syringe", FingerprintCategory.INJECTOR, 2,
                List.of("syringe")));
        list.add(proc("jecta", "Jecta", FingerprintCategory.INJECTOR, 4,
                List.of("jecta")));
        list.add(proc("generic-injector", "进程名含 injector 的通用启发式", FingerprintCategory.INJECTOR, 3,
                List.of("injector")));
        list.add(proc("kernel-injector", "内核注入工具", FingerprintCategory.INJECTOR, 5,
                List.of("kernel injector", "kernelinjector")));
        list.add(win("injector-window", "标题含 Injector 的通用窗口", FingerprintCategory.INJECTOR, 3,
                List.of(), List.of("Injector")));
        return list;
    }

    /**
     * Ghost Client。本体注入后不留进程与文件，指纹偏行为面：
     * 窗口特征 + 配置标记（configs()），无法依赖进程与模块通道。
     */
    private static List<CheatFingerprint> ghosts() {
        List<CheatFingerprint> list = new ArrayList<>();
        list.add(win("vape-v4", "Vape v4", FingerprintCategory.GHOST, 4,
                List.of("Vape v4"), List.of("Vape v4")));
        list.add(win("vape-v3", "Vape v3", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Vape v3")));
        // Vape 启动器是独立进程，注入后本体常驻 javaw。
        list.add(proc("vape-launcher", "Vape 启动器", FingerprintCategory.GHOST, 4,
                List.of("vape")));
        list.add(win("entropy-client", "Entropy", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Entropy Client")));
        list.add(win("dream-client", "Dream Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Dream Client")));
        list.add(win("drip-client", "Drip Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Drip Client")));
        list.add(win("whiteout-client", "Whiteout Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Whiteout Client")));
        list.add(win("raven-client", "Raven Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Raven Client", "Raven b4")));
        list.add(win("astolfo-client", "Astolfo", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Astolfo")));
        list.add(win("devergence-client", "Devergence", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Devergence")));
        list.add(win("rise-client", "Rise Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Rise Client")));
        list.add(win("tenacity-client", "Tenacity Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Tenacity Client")));
        list.add(win("slick-client", "Slick Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Slick Client")));
        list.add(win("timeless-client", "Timeless Client", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Timeless Client")));
        list.add(win("generic-ghost-window", "Ghost Client 通用窗口", FingerprintCategory.GHOST, 4,
                List.of(), List.of("Ghost Client")));
        return list;
    }

    /** 驱动级作弊，全部按 5 级处置；漏洞驱动条目同时覆盖 BYOVD 滥用面。 */
    private static List<CheatFingerprint> drivers() {
        List<CheatFingerprint> list = new ArrayList<>();
        // DBK64/DBVM 是 Cheat Engine 的内核驱动，与 memoryTools 的 CE 进程指纹互补。
        list.add(mod("dbk64-driver", "CE 内核驱动 DBK64", FingerprintCategory.DRIVER, 5,
                List.of("dbk64.sys", "dbk32.sys")));
        list.add(mod("dbvm-driver", "CE 虚拟机监控驱动 DBVM", FingerprintCategory.DRIVER, 5,
                List.of("dbvm.sys")));
        list.add(proc("kdmapper", "kdmapper 手动映射器", FingerprintCategory.DRIVER, 5,
                List.of("kdmapper")));
        list.add(mod("gdrv-driver", "Gigabyte 漏洞驱动 gdrv", FingerprintCategory.DRIVER, 5,
                List.of("gdrv.sys")));
        list.add(mod("iqvw64e-driver", "Intel 漏洞驱动 iqvw64e", FingerprintCategory.DRIVER, 5,
                List.of("iqvw64e.sys")));
        list.add(mod("rtcore64-driver", "MSI Afterburner 漏洞驱动 RTCore64", FingerprintCategory.DRIVER, 5,
                List.of("rtcore64.sys")));
        list.add(mod("dbutil-driver", "Dell 漏洞驱动 DBUtil", FingerprintCategory.DRIVER, 5,
                List.of("dbutil_2_3.sys")));
        list.add(mod("asrdrv-driver", "ASRock 漏洞驱动 AsrDrv", FingerprintCategory.DRIVER, 5,
                List.of("asrdrv.sys", "asrdrv103.sys")));
        list.add(mod("glckio2-driver", "ASUS 漏洞驱动 GLCKIO2", FingerprintCategory.DRIVER, 5,
                List.of("glckio2.sys", "glckio2")));
        // WinRing0 是硬件监控类合法软件也在用的驱动，靠白名单抑制误报。
        list.add(mod("winring0-driver", "WinRing0 内核访问驱动", FingerprintCategory.DRIVER, 5,
                List.of("winring0.sys", "winring0x64.sys", "winring0x64.dll")));
        return list;
    }

    /** 配置 / 脚本特征（AutoConfig 检测，文档 §2.2），标记串按契约存 windowTitles。 */
    private static List<CheatFingerprint> configs() {
        List<CheatFingerprint> list = new ArrayList<>();
        // P-10：ML 伪装相关的配置键。模型文件与普通配置混在一起，靠这些标记串区分。
        list.add(cfg("nanokalite-config", "NanokaLite 配置标记", FingerprintCategory.CLIENT, 4,
                List.of("nanokalite")));
        list.add(cfg("opai-config", "OPAI 配置标记", FingerprintCategory.CLIENT, 4,
                List.of("opai-client")));
        list.add(cfg("ml-rotation-config", "ML 转头配置标记", FingerprintCategory.CLIENT, 4,
                List.of("ml-rotation", "smooth-aim", "aim-assist-ml")));
        list.add(cfg("liquidbounce-config", "LiquidBounce 配置标记", FingerprintCategory.CLIENT, 4,
                List.of("liquidbounce")));
        list.add(cfg("wurst-config", "Wurst 配置标记", FingerprintCategory.CLIENT, 4,
                List.of(".wurst")));
        list.add(cfg("meteor-config", "Meteor 配置标记", FingerprintCategory.CLIENT, 4,
                List.of("meteor-client")));
        list.add(cfg("vape-config", "Vape 配置标记", FingerprintCategory.GHOST, 4,
                List.of("vape")));
        // 服务器下发 autoconfig 自动加载作弊模块是文档 §2.2 点名的行为面。
        list.add(cfg("autoconfig-generic", "AutoConfig 通用标记", FingerprintCategory.CLIENT, 3,
                List.of("autoconfig")));
        list.add(cfg("bypass-config-generic", "bypass-config 通用标记", FingerprintCategory.CLIENT, 3,
                List.of("bypass-config", "bypass_config")));
        list.add(cfg("clickgui-config", "ClickGUI 配置文件标记", FingerprintCategory.CLIENT, 3,
                List.of("clickgui")));
        list.add(cfg("killaura-config", "作弊模块名配置标记", FingerprintCategory.CLIENT, 3,
                List.of("killaura")));
        return list;
    }

    // ------------------------------------------------------------------ 构造辅助

    private static CheatFingerprint proc(String id, String name, FingerprintCategory category, int riskLevel,
                                         List<String> processNames) {
        return new CheatFingerprint(id, FingerprintType.PROCESS, name, processNames, List.of(), List.of(),
                List.of(), List.of(), List.of(), category, riskLevel);
    }

    private static CheatFingerprint win(String id, String name, FingerprintCategory category, int riskLevel,
                                        List<String> windowClasses, List<String> windowTitles) {
        return new CheatFingerprint(id, FingerprintType.WINDOW, name, List.of(), windowClasses, windowTitles,
                List.of(), List.of(), List.of(), category, riskLevel);
    }

    private static CheatFingerprint mod(String id, String name, FingerprintCategory category, int riskLevel,
                                        List<String> moduleNames) {
        return new CheatFingerprint(id, FingerprintType.MODULE, name, List.of(), List.of(), List.of(),
                moduleNames, List.of(), List.of(), category, riskLevel);
    }

    private static CheatFingerprint mem(String id, String name, FingerprintCategory category, int riskLevel,
                                        List<String> memoryPatterns) {
        return new CheatFingerprint(id, FingerprintType.MEMORY, name, List.of(), List.of(), List.of(),
                List.of(), List.of(), memoryPatterns, category, riskLevel);
    }

    private static CheatFingerprint cfg(String id, String name, FingerprintCategory category, int riskLevel,
                                        List<String> markers) {
        return new CheatFingerprint(id, FingerprintType.CONFIG, name, List.of(), List.of(), markers,
                List.of(), List.of(), List.of(), category, riskLevel);
    }
}
