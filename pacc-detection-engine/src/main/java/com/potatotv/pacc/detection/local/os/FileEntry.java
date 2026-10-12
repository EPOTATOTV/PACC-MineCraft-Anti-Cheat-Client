package com.potatotv.pacc.detection.local.os;

/** 游戏目录文件条目（GameDirGuard 完整性校验输入）。 */
public record FileEntry(String relativePath, long size, String sha256) {

    /** 是否为目录项（目录没有哈希与有效大小）。 */
    public boolean isDirectory() {
        return sha256 == null || sha256.isEmpty();
    }
}
