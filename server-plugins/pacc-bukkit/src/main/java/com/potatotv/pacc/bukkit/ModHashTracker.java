package com.potatotv.pacc.bukkit;

import com.potatotv.pacc.server.core.protocol.ModHashParser;
import com.potatotv.pacc.server.core.protocol.ModHashReport;
import com.potatotv.pacc.server.core.report.ModProofReport;
import com.potatotv.pacc.server.core.report.PtvServerReporter;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

import java.lang.System.Logger;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 记录每个玩家通过 {@code pacc:mods} 通道上报的 Mod 文件哈希（小写 sha256）。
 * 通道名、载荷格式与约束见 {@link ModHashParser}；客户端发送端由 {@code pacc-forge-mod} /
 * {@code pacc-fabric-mod} 实现。同一玩家重复上报时以最新一次为准。
 *
 * <p>v2 报文带质询证明时，{@link #proof(String)} 取得到原始 proof；核验由 PTV 的
 * {@code /api/v1/server/mod-verify} 完成——共享签名密钥属于客户端与 PTV，不能下发给游戏服。</p>
 */
public final class ModHashTracker implements PluginMessageListener {

    private static final Logger LOG = System.getLogger(ModHashTracker.class.getName());

    private final ConcurrentHashMap<String, ModHashReport> reports = new ConcurrentHashMap<>();
    /** playerUuid → 质询核验结果；null 表示还没收到证明。 */
    private final ConcurrentHashMap<String, Boolean> verified = new ConcurrentHashMap<>();
    private final PtvServerReporter reporter;
    private final String serverId;

    /** 不接 PTV 核验的构造（测试或未配置上报地址时）。 */
    public ModHashTracker() {
        this(null, "");
    }

    public ModHashTracker(PtvServerReporter reporter, String serverId) {
        this.reporter = reporter;
        this.serverId = serverId == null ? "" : serverId;
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        if (player == null) {
            return;
        }
        try {
            Optional<ModHashReport> report = ModHashParser.parse(message);
            if (report.isEmpty()) {
                // 畸形 / 超限载荷已在解析器内记日志并丢弃，这里不做额外处理。
                return;
            }
            String playerId = player.getUniqueId().toString();
            ModHashReport parsed = report.get();
            reports.put(playerId, parsed);
            submitProof(playerId, parsed);
        } catch (RuntimeException ignored) {
            // 客户端可发任意畸形载荷，这里必须吞掉，绝不能因为入站数据打断服务端流程。
        }
    }

    private void submitProof(String playerId, ModHashReport report) {
        ModHashReport.Proof proof = report.proof();
        if (proof == null || reporter == null) {
            return;
        }
        reporter.reportModProof(new ModProofReport(
                serverId, playerId, proof.challengeId(), proof.nonce(),
                report.mods(), proof.elapsedMs(), proof.signature()), pass -> {
            verified.put(playerId, Boolean.TRUE.equals(pass));
            if (!Boolean.TRUE.equals(pass)) {
                LOG.log(Logger.Level.WARNING, "Mod 质询核验未通过 player=" + playerId);
            }
        });
    }

    public List<String> hashes(String playerId) {
        ModHashReport report = reports.get(playerId);
        return report == null ? List.of() : List.copyOf(report.hashes());
    }

    /** v2 质询证明；v1 报文或未上报时为空。 */
    public Optional<ModHashReport.Proof> proof(String playerId) {
        ModHashReport report = reports.get(playerId);
        return report == null ? Optional.empty() : Optional.ofNullable(report.proof());
    }

    /**
     * PTV 核验结果：true 通过，false 失败，null 尚未拿到结果（v1 报文或无上报通道）。
     */
    public Boolean proofVerified(String playerId) {
        return verified.get(playerId);
    }

    public void clear(String playerId) {
        reports.remove(playerId);
        verified.remove(playerId);
    }
}
