package com.potatotv.pacc.detection.local.os;

/**
 * 目标进程模块信息（L2 模块审计输入）。
 *
 * @param name       模块文件名（可被伪造，内容校验靠 sha256）
 * @param path       模块完整路径
 * @param sha256     模块文件 SHA-256（小写十六进制；读取失败为空串）
 * @param baseAddress 模块加载基址
 * @param size       模块映像大小（字节）
 */
public record ModuleInfo(String name, String path, String sha256, long baseAddress, long size) {

    /** 模块映像地址范围是否包含给定地址（远程线程起始地址分析用）。 */
    public boolean contains(long address) {
        return address >= baseAddress && address < baseAddress + size;
    }
}
