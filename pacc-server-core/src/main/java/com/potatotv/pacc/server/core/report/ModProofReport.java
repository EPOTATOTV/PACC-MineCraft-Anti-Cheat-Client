package com.potatotv.pacc.server.core.report;

import com.potatotv.pacc.server.core.protocol.ModHashReport;

import java.util.List;

/**
 * Mod 清单质询证明上报（R-09）。
 *
 * <p>游戏服插件不知道玩家 PTEID，只把质询参数、Mod 清单与签名转给 PTV；
 * PTEID 由 PTV 侧质询记录给出，插件无法伪造归属。</p>
 *
 * @param serverId    上报的服务端标识
 * @param playerUuid  玩家 UUID（仅用于服务端侧排查）
 * @param challengeId PTV 签发的一次性质询 ID
 * @param nonce       质询随机数
 * @param mods        Mod 清单
 * @param elapsedMs   客户端自述签名耗时
 * @param signature   HMAC-SHA256 签名
 */
public record ModProofReport(String serverId, String playerUuid, String challengeId, String nonce,
                             List<ModHashReport.ModEntry> mods, long elapsedMs, String signature) {

    public ModProofReport {
        mods = mods == null ? List.of() : List.copyOf(mods);
    }
}
