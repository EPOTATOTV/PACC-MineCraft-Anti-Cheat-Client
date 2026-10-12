package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.detection.server.prediction.OffsetResult;
import com.potatotv.pacc.detection.server.prediction.PlayerState;
import com.potatotv.pacc.detection.server.prediction.Position;
import com.potatotv.pacc.detection.server.prediction.PredictionEngine;
import com.potatotv.pacc.detection.server.prediction.TickInput;
import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T2 合法玩家模拟器验收：覆盖走/跑/疾跑/跳跃/潜行/游泳/鞘翅滑翔/乘船/搭桥/高延迟等场景，
 * 断言物理预测偏差不超容差、完整检测链零告警，并核对豁免场景的引擎开关事实。
 */
class LegitSimulatorTest {

    private static final long SEED = 20261012L;
    private static final int SAMPLES = Math.max(2000, Integer.getInteger("pacc.sim.samples", 2000));
    private static final double MAX_FALSE_POSITIVE_RATE = 0.0001;

    private static final List<SimScenario> MOVEMENT_SCENARIOS = List.of(
            SimScenario.WALK, SimScenario.RUN, SimScenario.SPRINT, SimScenario.JUMP, SimScenario.SNEAK,
            SimScenario.SWIM, SimScenario.ELYTRA, SimScenario.BOAT, SimScenario.BRIDGE, SimScenario.HIGH_LATENCY);

    @Test
    void 合法流物理预测偏差不超容差且无违规() {
        PredictionEngine engine = new PredictionEngine();
        long total = 0;
        long toleranceBreaches = 0;
        long violations = 0;

        for (SimScenario scenario : MOVEMENT_SCENARIOS) {
            List<LegitMoveSample> samples =
                    new LegitTrajectoryGenerator(scenario, "legit-" + scenario, SEED, SAMPLES).generate();
            for (LegitMoveSample sample : samples) {
                total++;
                Optional<OffsetResult> offset =
                        engine.evaluate(sample.enginePrev(), sample.input(), sample.engineActual());
                assertTrue(offset.isPresent(), scenario + " 非豁免场景应产出偏差结果");
                if (!offset.get().withinTolerance()) {
                    toleranceBreaches++;
                }
                if (engine.check(sample.enginePrev(), sample.input(), sample.engineActual()).isPresent()) {
                    violations++;
                }
            }
        }
        System.out.printf("[T2] 物理预测样本=%d 超容差=%d 违规=%d%n", total, toleranceBreaches, violations);
        assertEquals(0, toleranceBreaches, "合法流不应出现超出容差的物理预测偏差");
        assertEquals(0, violations, "合法流不应产生物理预测违规");
    }

    @Test
    void 合法流在完整检测引擎上零告警() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        Map<SimScenario, long[]> perScenario = new EnumMap<>(SimScenario.class);
        long total = 0;
        long falsePositives = 0;

        List<SimScenario> all = new java.util.ArrayList<>(MOVEMENT_SCENARIOS);
        all.add(SimScenario.CREATIVE); // 创造模式豁免场景一并纳入误报率统计

        for (SimScenario scenario : all) {
            SimServerAdapter adapter = new SimServerAdapter();
            DetectionEngine engine = SimEngines.fullEngine(config, adapter);
            List<LegitMoveSample> samples =
                    new LegitTrajectoryGenerator(scenario, "legit-" + scenario, SEED, SAMPLES).generate();
            long scenarioSamples = 0;
            long scenarioFp = 0;
            for (LegitMoveSample sample : samples) {
                List<DetectionResult> results = engine.handleMove(sample.serverFrom(), sample.serverTo());
                scenarioSamples++;
                if (!results.isEmpty()) {
                    scenarioFp++;
                }
            }
            perScenario.put(scenario, new long[] {scenarioSamples, scenarioFp});
            total += scenarioSamples;
            falsePositives += scenarioFp;
            assertTrue(adapter.alerts().isEmpty(), scenario + " 不应产生告警");
            assertTrue(adapter.punishments().isEmpty(), scenario + " 不应产生处置");
        }

        double rate = (double) falsePositives / total;
        System.out.println("[T2] 完整检测引擎误报率统计：");
        for (Map.Entry<SimScenario, long[]> entry : perScenario.entrySet()) {
            long[] stat = entry.getValue();
            System.out.printf("     %-14s 样本=%-6d 误报=%d%n", entry.getKey(), stat[0], stat[1]);
        }
        System.out.printf("[T2] 合计样本=%d 误报=%d 误报率=%.6f（阈值 %.4f）%n",
                total, falsePositives, rate, MAX_FALSE_POSITIVE_RATE);

        assertTrue(rate <= MAX_FALSE_POSITIVE_RATE,
                "合法流误报率 " + rate + " 超过阈值 " + MAX_FALSE_POSITIVE_RATE);
    }

    @Test
    void 网络模型延迟与批处理可复现且不改变逻辑tick时间() {
        NetworkModel modelA = new NetworkModel(SEED);
        NetworkModel modelB = new NetworkModel(SEED);
        for (int i = 0; i < 100; i++) {
            long delay = modelA.nextDelayMs();
            assertEquals(delay, modelB.nextDelayMs(), "同一 seed 的延迟序列必须一致");
            assertTrue(delay >= NetworkModel.MIN_DELAY_MS && delay <= NetworkModel.MAX_DELAY_MS + NetworkModel.MAX_JITTER_MS,
                    "延迟应落在采样区间内，实际 " + delay);
        }
        List<List<Integer>> plan = new NetworkModel(SEED).deliveryPlan(500);
        int covered = 0;
        for (List<Integer> batch : plan) {
            assertFalse(batch.isEmpty());
            assertTrue(batch.size() <= NetworkModel.MAX_BATCH);
            for (int index : batch) {
                assertEquals(covered++, index, "批处理不得重排包序");
            }
        }
        assertEquals(500, covered);
        System.out.printf("[T2] 网络模型：批数=%d 覆盖包=%d%n", plan.size(), covered);
    }

    @Test
    void 豁免场景引擎开关事实核对() {
        // 创造模式：MoveCheck 与 PredictionEngine 均有豁免开关
        MoveCheck moveCheck = new MoveCheck(ServerPluginConfig.defaults());
        assertTrue(moveCheck.onMove(
                pos("c", 0, 64, 0, 1000, true, GameMode.CREATIVE),
                pos("c", 5.0, 90, 0, 1050, false, GameMode.CREATIVE)).isEmpty(),
                "创造模式应跳过移动检查");

        PredictionEngine engine = new PredictionEngine();
        PlayerState creative = new PlayerState("c", 0, 64, 0, 0, 0, 0, 0f, 0f, true,
                false, false, false, false, false, false, false, false, false, false, false,
                false, "creative", 0, Map.of(), 0L);
        assertTrue(engine.evaluate(creative, TickInput.idle(0L), new Position(0, 90, 0)).isEmpty(),
                "创造模式应豁免物理预测");

        // 允许飞行：仅 detection-engine 的 PlayerState.flightAllowed 有开关（服务端 intel 检查硬编码为 false）
        PlayerState flying = new PlayerState("f", 0, 64, 0, 0, 0, 0, 0f, 0f, false,
                false, false, false, false, false, false, false, false, false, false, false,
                true, "survival", 0, Map.of(), 0L);
        assertTrue(engine.evaluate(flying, TickInput.idle(0L), new Position(0, 90, 0)).isEmpty(),
                "允许飞行应豁免物理预测");

        // 开局缓冲：detection-engine 与 server-core 均无对应开关（源码中不存在 join/buffer/grace 概念）
        System.out.println("[T2] 豁免事实核对：创造模式=有开关；允许飞行=仅引擎级 PlayerState.flightAllowed，"
                + "server-core 的 PredictionIntelCheck 硬编码 false（服务端无该豁免）；开局缓冲=无任何开关");
    }

    private static PlayerPosition pos(String id, double x, double y, double z, long ts, boolean onGround, GameMode mode) {
        return new PlayerPosition(id, new com.potatotv.pacc.server.core.model.Position(x, y, z), 0f, 0f, ts, onGround, mode);
    }
}
