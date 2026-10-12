package com.potatotv.pacc.server.core.protocol;

import java.util.List;

/**
 * 客户端上报的 Mod 文件哈希集合，是 {@code pacc:mods} 载荷（见 {@link ModHashParser}）的解析结果模型。
 *
 * <p>{@code sha256} 在解析阶段已归一为小写；{@code id} 为 modid，可为空。该类不可变。
 */
public record ModHashReport(List<ModEntry> mods) {

    public ModHashReport {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }

    /**
     * 单条 Mod 条目。
     *
     * @param id     modid，可为空
     * @param sha256 Mod 文件的 SHA-256，64 位小写十六进制
     */
    public record ModEntry(String id, String sha256) {
    }

    /** 全部条目的 sha256，保持上报顺序。 */
    public List<String> hashes() {
        return mods.stream().map(ModEntry::sha256).toList();
    }
}
