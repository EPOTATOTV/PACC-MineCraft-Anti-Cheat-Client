package com.potatotv.pacc.detection.local.os;

/**
 * 指向目标进程的外部句柄信息（L3 句柄审计输入）。
 *
 * @param sourcePid         持有句柄的进程 ID
 * @param sourceProcessName 持有句柄的进程名
 * @param accessMask        访问掩码（{@code 0x1FFFFF} 即 PROCESS_ALL_ACCESS，内存工具特征）
 */
public record HandleInfo(long sourcePid, String sourceProcessName, int accessMask) {

    /** PROCESS_ALL_ACCESS。 */
    public static final int PROCESS_ALL_ACCESS = 0x1FFFFF;

    /** 是否为完全访问句柄。 */
    public boolean fullAccess() {
        return (accessMask & PROCESS_ALL_ACCESS) == PROCESS_ALL_ACCESS;
    }
}
