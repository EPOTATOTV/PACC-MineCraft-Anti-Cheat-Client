package com.potatotv.pacc.detection.signature;

/** 作弊软件类别（技术文档 §2.1 四大类别，§6.1 category 字段）。 */
public enum FingerprintCategory {
    /** 模块化作弊客户端（Wurst / LiquidBounce / Meteor 等）。 */
    CLIENT,
    /** 内存修改工具（CE / ArtMoney 等）。 */
    MEMORY_TOOL,
    /** 注入器（DLL / Mod 注入工具）。 */
    INJECTOR,
    /** Ghost Client（Vape / Entropy / Dream / Drip 等隐藏注入）。 */
    GHOST,
    /** 驱动级作弊。 */
    DRIVER
}
