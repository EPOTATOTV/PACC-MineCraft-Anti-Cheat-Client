package com.potatotv.pacc.detection.local.os;

/**
 * 操作系统探针抽象（技术文档 §六、玩家端检测核心方案）。
 *
 * <p>玩家端检测的数据只来自本机（PACC 硬约束）：进程枚举、模块枚举、线程枚举、
 * 窗口枚举、句柄审计、模块代码段读取。Windows 实现由 ptv-client 的系统探针提供
 * （JNA/Win32），本模块只定义契约，保证检测逻辑与平台绑定解耦、可测试。
 *
 * <p>实现要求：所有方法不得抛出未受检异常（失败返回空集合 / 空数组）；
 * 只读取与目标游戏进程相关的数据，禁止越界采集。
 */
public interface OsProbe {

    /** 枚举本机所有可见进程。 */
    java.util.List<ProcessInfo> processes();

    /** 枚举目标进程加载的全部模块（含路径与 SHA-256）。 */
    java.util.List<ModuleInfo> modules(long pid);

    /** 枚举目标进程的全部线程及其起始地址信息。 */
    java.util.List<ThreadInfo> threads(long pid);

    /** 枚举本机全部顶层窗口（标题 + 类名 + 所属进程）。 */
    java.util.List<WindowInfo> windows();

    /** 审计持有目标进程句柄的其他进程（内存工具特征）。 */
    java.util.List<HandleInfo> handlesTo(long pid);

    /**
     * 读取目标进程指定模块的代码段前 {@code maxBytes} 字节（API Hook 校验用）。
     * 读取失败返回空数组。
     */
    byte[] readModuleCode(long pid, ModuleInfo module, int maxBytes);

    /** 列出游戏目录的文件清单（相对路径 + 大小 + SHA-256，目录项 sha256 为空）。 */
    java.util.List<FileEntry> listGameDirectory(String gameDir);
}
