package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.profile.BehaviorProfile;
import com.potatotv.pacc.detection.signature.SignatureLibrary;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输入层检测：机械点击序列、人类随机间隔、样本不足与节流。 */
class InputAnalyzerTest {

    private LocalScanContext ctx() {
        return new LocalScanContext(
                "player-1", 1234, "d:\\game", new FakeOsProbe(), SignatureLibrary.of(List.of()), true);
    }

    @Test
    void 机械固定间隔点击出证据() {
        InputAnalyzer analyzer = new InputAnalyzer();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 20; i++) {
            analyzer.recordClick(t + i * 50L);
        }

        List<Evidence> out = analyzer.analyze("player-1");

        assertEquals(2, out.size());
        assertEquals(Layer.INPUT, out.get(0).layer());
        // 方差 0、熵 0、GCD 模式满配 → 机械程度 1.0
        assertEquals(1.0, out.get(0).confidence(), 1e-9);
        assertEquals(0.6, out.get(1).confidence(), 1e-9);
        assertTrue(out.get(0).detail().contains("机械"));
        assertTrue(out.get(1).detail().contains("倍数"));
    }

    @Test
    void 人类随机间隔不出证据() {
        // 19 个不规则间隔：分布宽（熵 > 3 bit）、两两 GCD 无固定倍数
        int[] gaps = {421, 95, 271, 63, 342, 148, 491, 118, 229, 54,
                381, 176, 544, 254, 137, 602, 102, 241, 445};
        InputAnalyzer analyzer = new InputAnalyzer();
        long t = 1_700_000_000_000L;
        analyzer.recordClick(t);
        for (int gap : gaps) {
            t += gap;
            analyzer.recordClick(t);
        }

        List<Evidence> out = analyzer.analyze("player-1");

        assertTrue(out.isEmpty());
    }

    @Test
    void 样本不足不出证据() {
        InputAnalyzer analyzer = new InputAnalyzer();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 5; i++) {
            analyzer.recordClick(t + i * 50L);
        }

        List<Evidence> out = analyzer.analyze("player-1");

        assertTrue(out.isEmpty());
    }

    @Test
    void 无新数据时scan返回空() {
        InputAnalyzer analyzer = new InputAnalyzer();

        assertTrue(analyzer.scan(ctx()).isEmpty());
    }

    @Test
    void 产出后窗口节流() {
        InputAnalyzer analyzer = new InputAnalyzer();
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 20; i++) {
            analyzer.recordClick(t + i * 50L);
        }
        assertEquals(2, analyzer.analyze("player-1").size());

        // 下一窗口的新数据在节流期内不重复产出
        t += 31_000;
        for (int i = 0; i < 20; i++) {
            analyzer.recordClick(t + i * 50L);
        }
        assertTrue(analyzer.analyze("player-1").isEmpty());
    }

    @Test
    void scan把画像喂入会话画像() {
        BehaviorProfile behavior = new BehaviorProfile();
        InputAnalyzer analyzer = new InputAnalyzer(behavior);
        long t = 1_700_000_000_000L;
        for (int i = 0; i < 20; i++) {
            analyzer.recordClick(t + i * 50L);
        }
        analyzer.scan(ctx());

        assertEquals(1, behavior.clickWindows().size());
        assertEquals(20, behavior.clickWindows().get(0).clickCount());
    }
}
