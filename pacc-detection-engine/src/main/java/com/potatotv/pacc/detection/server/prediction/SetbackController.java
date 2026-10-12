package com.potatotv.pacc.detection.server.prediction;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 回撤（setback）控制器（技术文档 §4.2 SetbackController）。
 *
 * <p>PACC 服务端模式默认仅告警不处置；本控制器只负责「记录建议回撤点」，
 * 是否真正传送玩家由接入方按自身配置决定——检测引擎本身不执行任何位移。
 * 违规确认时写入目标点，上层在下一个 tick 取走并执行，取走即清。
 */
public final class SetbackController {

    private final ConcurrentHashMap<String, Position> pending = new ConcurrentHashMap<>();

    /** 记录一名玩家的建议回撤点（覆盖旧值）。 */
    public void setback(String playerId, Position target) {
        pending.put(playerId, target);
    }

    /** 取走待执行的回撤点（取走即清），无则 empty。 */
    public Optional<Position> pendingSetback(String playerId) {
        return Optional.ofNullable(pending.remove(playerId));
    }

    /** 清除回撤点（如玩家自行回到合法位置）。 */
    public void clear(String playerId) {
        pending.remove(playerId);
    }
}
