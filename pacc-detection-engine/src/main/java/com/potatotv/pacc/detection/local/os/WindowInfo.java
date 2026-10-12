package com.potatotv.pacc.detection.local.os;

/** 本机顶层窗口信息（L1 窗口指纹输入）。 */
public record WindowInfo(long pid, String title, String windowClass) {
}
