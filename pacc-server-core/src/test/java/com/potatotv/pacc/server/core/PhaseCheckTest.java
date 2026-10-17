package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.PhaseCheck;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.PacketView;
import com.potatotv.pacc.server.core.packet.BlockProbe;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhaseCheckTest {

    @Test
    void withoutProbeCheckIsInactive() {
        PhaseCheck check = new PhaseCheck(null);
        assertFalse(check.available());
        assertTrue(check.onPacket(position("p1", 1000, 10, 64, 10)).isEmpty());
    }

    @Test
    void singleSolidSampleDoesNotFlag() {
        PhaseCheck check = new PhaseCheck(new FixedProbe(BlockProbe.SolidState.SOLID));
        assertTrue(check.available());
        assertTrue(check.onPacket(position("p1", 1000, 10, 64, 10)).isEmpty());
    }

    @Test
    void consecutiveSolidSamplesFlagPhase() {
        PhaseCheck check = new PhaseCheck(new FixedProbe(BlockProbe.SolidState.SOLID));
        check.onPacket(position("p1", 1000, 10, 64, 10));
        List<DetectionResult> results = check.onPacket(position("p1", 1300, 10, 64, 10));
        assertEquals(1, results.size());
        assertEquals("PHASE", results.get(0).cheatType());
    }

    @Test
    void passableBlocksNeverFlag() {
        PhaseCheck check = new PhaseCheck(new FixedProbe(BlockProbe.SolidState.PASSABLE));
        for (int i = 0; i < 5; i++) {
            assertTrue(check.onPacket(position("p1", 1000L + i * 300, 10, 64, 10)).isEmpty());
        }
    }

    @Test
    void unknownStateAbortsStrikeChain() {
        // 第一次实心、第二次拿不到数据：不能靠「查不了」凑出违规
        PhaseCheck check = new PhaseCheck(new SequenceProbe(
                BlockProbe.SolidState.SOLID, BlockProbe.SolidState.UNKNOWN, BlockProbe.SolidState.SOLID));
        check.onPacket(position("p1", 1000, 10, 64, 10));
        assertTrue(check.onPacket(position("p1", 1300, 10, 64, 10)).isEmpty());
        assertTrue(check.onPacket(position("p1", 1600, 10, 64, 10)).isEmpty());
    }

    @Test
    void samplingIsRateLimited() {
        PhaseCheck check = new PhaseCheck(new FixedProbe(BlockProbe.SolidState.SOLID));
        check.onPacket(position("p1", 1000, 10, 64, 10));
        // 同一毫秒窗口内的第二个包不采样，因此凑不出第二次命中
        assertTrue(check.onPacket(position("p1", 1100, 10, 64, 10)).isEmpty());
        assertTrue(check.onPacket(position("p1", 1200, 10, 64, 10)).isEmpty());
    }

    @Test
    void resetClearsState() {
        PhaseCheck check = new PhaseCheck(new FixedProbe(BlockProbe.SolidState.SOLID));
        check.onPacket(position("p1", 1000, 10, 64, 10));
        check.reset("p1");
        assertTrue(check.onPacket(position("p1", 1300, 10, 64, 10)).isEmpty());
    }

    private static PacketView position(String playerId, long ts, double x, double y, double z) {
        return new PacketView(playerId, "PLAYER_POSITION", ts,
                Map.of("x", Double.toString(x), "y", Double.toString(y), "z", Double.toString(z)));
    }

    /** 固定状态的探针。 */
    private record FixedProbe(BlockProbe.SolidState state) implements BlockProbe {
        @Override
        public SolidState stateAt(String playerId, int blockX, int blockY, int blockZ) {
            return state;
        }
    }

    /** 每个包查两次方块（脚部 + 头部），这里让同一个包的两侧返回同一个预设状态。 */
    private static final class SequenceProbe implements BlockProbe {

        private final SolidState[] states;
        private int calls;

        private SequenceProbe(SolidState... states) {
            this.states = states;
        }

        @Override
        public SolidState stateAt(String playerId, int blockX, int blockY, int blockZ) {
            int index = calls++ / 2;
            if (index >= states.length) {
                return SolidState.UNKNOWN;
            }
            return states[index];
        }
    }
}
