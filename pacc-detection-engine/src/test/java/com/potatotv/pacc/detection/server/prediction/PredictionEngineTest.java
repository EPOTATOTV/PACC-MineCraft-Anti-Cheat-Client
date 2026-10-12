package com.potatotv.pacc.detection.server.prediction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;

class PredictionEngineTest {

    private static PlayerState state(String pid, double x, double y, double z,
                                     double vx, double vy, double vz,
                                     boolean onGround, String gameMode, boolean flightAllowed) {
        return new PlayerState(pid, x, y, z, vx, vy, vz, 0f, 0f, onGround,
                false, false, false, false, false, false, false, false, false, false,
                false, flightAllowed, gameMode, 0, Map.of(), 0L);
    }

    @Test
    void gravityStepMatchesVanillaFormula() {
        // 跳跃初速 0.42 的下一 tick：(0.42 - 0.08) * 0.98 ≈ 0.3332
        assertEquals(0.3332, PhysicsSimulator.stepVertical(0.42), 1e-9);
    }

    @Test
    void normalPredictorJumpProducesVanillaFirstTick() {
        PlayerState prev = state("p", 0, 64, 0, 0, 0, 0, true, "survival", false);
        TickInput jump = new TickInput(0f, 0f, true, false, false, 0L);
        Set<PredictedPosition> predicted = new NormalPredictor().predict(prev, jump);
        assertTrue(predicted.stream().anyMatch(p ->
                Math.abs(p.vy() - PhysicsSimulator.JUMP_VELOCITY) < 1e-9
                        && Math.abs(p.y() - 64.42) < 1e-9));
    }

    @Test
    void creativeAndFlightAreExempt() {
        PredictionEngine engine = new PredictionEngine();
        PlayerState creative = state("p", 0, 64, 0, 0, 0, 0, true, "creative", false);
        PlayerState flying = state("p", 0, 64, 0, 0, 0, 0, false, "survival", true);
        TickInput idle = TickInput.idle(0L);
        Position far = new Position(0, 70, 0);
        assertTrue(engine.evaluate(creative, idle, far).isEmpty());
        assertTrue(engine.check(creative, idle, far).isEmpty());
        assertTrue(engine.evaluate(flying, idle, far).isEmpty());
        assertTrue(engine.check(flying, idle, far).isEmpty());
    }

    @Test
    void withinToleranceProducesNoEvidence() {
        PredictionEngine engine = new PredictionEngine();
        // onGround 无跳跃：vy' = (0-0.08)*0.98 = -0.0784，候选 y = 63.9216 ± 0.03
        PlayerState prev = state("p", 0, 64, 0, 0, 0, 0, true, "survival", false);
        TickInput idle = TickInput.idle(0L);
        Position actual = new Position(0, 63.9216, 0);
        assertTrue(engine.evaluate(prev, idle, actual).orElseThrow().withinTolerance());
        assertTrue(engine.check(prev, idle, actual).isEmpty());
    }

    @Test
    void impossibleOffsetProducesFullConfidenceEvidence() {
        PredictionEngine engine = new PredictionEngine();
        PlayerState prev = state("p", 0, 64, 0, 0, 0, 0, true, "survival", false);
        TickInput idle = TickInput.idle(0L);
        Position actual = new Position(0, 67.5, 0);
        OffsetResult offset = engine.evaluate(prev, idle, actual).orElseThrow();
        assertFalse(offset.withinTolerance());
        assertTrue(offset.total() > 1.0);
        Evidence evidence = engine.check(prev, idle, actual).orElseThrow();
        assertEquals("prediction-engine", evidence.detectorId());
        assertEquals(Layer.BEHAVIOR, evidence.layer());
        assertEquals(1.0, evidence.confidence());
        assertTrue(evidence.detail().contains("predicted("));
        assertTrue(evidence.detail().contains("actual("));
        assertTrue(evidence.detail().contains("offset="));
    }

    @Test
    void worldReplicaFiltersCandidatesInsideSolidBlocks() {
        WorldReplica world = WorldReplica.empty();
        world.applyBlockChange(0, 64, 0, 1);
        PredictionEngine withWorld = new PredictionEngine(Map.of("p", world));
        PredictionEngine withoutWorld = new PredictionEngine();
        // 候选 y = {64.99, 65.02, 64.96}，方块占 y∈[64,65)：两个候选被世界剔除，
        // 实际位置 64.96 只能对上 65.02 → 垂直偏差 0.06 > 0.03 容忍 → 出证
        PlayerState prev = state("p", 0, 65.0684, 0, 0, 0, 0, false, "survival", false);
        TickInput idle = TickInput.idle(0L);
        Position actual = new Position(0, 64.96, 0);
        assertTrue(withoutWorld.check(prev, idle, actual).isEmpty());
        Evidence evidence = withWorld.check(prev, idle, actual).orElseThrow();
        assertTrue(evidence.confidence() > 0.5);
        assertTrue(evidence.confidence() <= 1.0);
    }

    @Test
    void nullAndExemptInputsAreHandled() {
        PredictionEngine engine = new PredictionEngine();
        assertTrue(engine.evaluate(null, TickInput.idle(0L), new Position(0, 0, 0)).isEmpty());
        assertTrue(engine.check(null, null, null).isEmpty());
    }

    @Test
    void latencyMedianAndPendingTracking() {
        LatencyCompensator compensator = new LatencyCompensator();
        long base = System.currentTimeMillis();
        int first = compensator.sendTransaction();
        int second = compensator.sendTransaction();
        int third = compensator.sendTransaction();
        assertTrue(first != second && second != third);
        assertTrue(compensator.isPending(first));
        compensator.ack(first, base + 100);
        compensator.ack(second, base + 300);
        compensator.ack(third, base + 200);
        assertFalse(compensator.isPending(first));
        long median = compensator.estimatedLatencyMs();
        assertTrue(median >= 150 && median <= 250, "中位数应接近 200，实际 " + median);
        // 未知事务的 ack 被忽略
        compensator.ack(999, base + 9999);
        assertEquals(3, compensator.ackedCount());
        assertEquals(3, compensator.sentCount());
        // 无样本时返回 -1
        assertEquals(-1, new LatencyCompensator().estimatedLatencyMs());
    }

    @Test
    void setbackIsTakeAndClear() {
        SetbackController controller = new SetbackController();
        Position target = new Position(10, 64, 10);
        controller.setback("p", target);
        assertEquals(target, controller.pendingSetback("p").orElseThrow());
        assertTrue(controller.pendingSetback("p").isEmpty());
        controller.setback("p", target);
        controller.clear("p");
        assertTrue(controller.pendingSetback("p").isEmpty());
    }
}
