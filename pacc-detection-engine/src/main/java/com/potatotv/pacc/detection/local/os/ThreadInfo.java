package com.potatotv.pacc.detection.local.os;

/**
 * 目标进程线程信息（L2 远程线程检测输入）。
 *
 * @param threadId          线程 ID
 * @param startAddress      线程起始地址
 * @param startModuleName   起始地址所在模块名（不在任何模块范围内时为空串）
 * @param startInMappedImage 起始地址是否落在已映射模块内；false 即疑似 shellcode 注入
 */
public record ThreadInfo(long threadId, long startAddress, String startModuleName, boolean startInMappedImage) {
}
