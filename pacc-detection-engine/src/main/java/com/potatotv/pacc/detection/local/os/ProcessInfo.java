package com.potatotv.pacc.detection.local.os;

/** 本机进程信息（L1 进程扫描输入）。 */
public record ProcessInfo(long pid, String name, String executablePath) {
}
