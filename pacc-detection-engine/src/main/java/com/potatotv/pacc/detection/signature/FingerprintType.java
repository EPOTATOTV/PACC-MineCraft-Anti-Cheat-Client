package com.potatotv.pacc.detection.signature;

/** 指纹匹配通道（技术文档 §6.1 FingerprintType）。 */
public enum FingerprintType {
    /** 进程指纹。 */
    PROCESS,
    /** 窗口指纹（窗口类名 / 标题）。 */
    WINDOW,
    /** 模块指纹（注入 DLL / Mod 哈希与类名）。 */
    MODULE,
    /** 内存特征码。 */
    MEMORY,
    /** 作弊配置 / 脚本特征。 */
    CONFIG
}
