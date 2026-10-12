package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.detection.server.prediction.PlayerState;
import com.potatotv.pacc.detection.server.prediction.TickInput;

/**
 * 一个「模拟玩家·tick 样本」。
 *
 * <p>同一 tick 同时携带两套视角的数据：
 * <ul>
 *   <li>{@link #enginePrev}/{@link #input}/{@link #engineActual}：喂给 detection-engine 的
 *       {@code PredictionEngine}，用于断言物理预测偏差在容差内；</li>
 *   <li>{@link #serverFrom}/{@link #serverTo}：喂给 pacc-server-core 的 {@code DetectionEngine.handleMove}，
 *       用于断言完整检测链零告警。</li>
 * </ul>
 */
record LegitMoveSample(
        String playerId,
        PlayerState enginePrev,
        TickInput input,
        com.potatotv.pacc.detection.server.prediction.Position engineActual,
        PlayerPosition serverFrom,
        PlayerPosition serverTo) {
}
