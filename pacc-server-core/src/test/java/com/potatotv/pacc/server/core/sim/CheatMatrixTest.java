package com.potatotv.pacc.server.core.sim;

import com.potatotv.pacc.server.core.engine.DetectionEngine;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3 作弊配置矩阵验收：对来源 × 四档 × 各模块的注入规格逐一喂 server-core 的
 * {@code DetectionEngine}，统计检出率（按模块 / 来源 / 档位三视图），输出绕过与未实现清单。
 *
 * <p>断言：每个「已有对应检测项」的模块检出率 ≥ 0.95。
 * 没有对应检测项的模块单独列为「未实现检测项」，不计入失败、不伪造通过。
 */
class CheatMatrixTest {

    private static final double MIN_DETECTION_RATE = 0.95;

    @Test
    void 作弊注入检出率达标并输出绕过与未实现清单() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        List<CheatSpec> specs = CheatConfigMatrix.build();

        Map<CheatModule, long[]> byModule = new EnumMap<>(CheatModule.class);
        Map<CheatSource, long[]> bySource = new EnumMap<>(CheatSource.class);
        Map<CheatTier, long[]> byTier = new EnumMap<>(CheatTier.class);
        List<String> bypass = new ArrayList<>();

        for (CheatSpec spec : specs) {
            SimServerAdapter adapter = new SimServerAdapter();
            DetectionEngine engine = SimEngines.fullEngine(config, adapter);
            List<DetectionResult> results = CheatInjector.inject(engine, spec);
            boolean hit = results.stream().anyMatch(r -> spec.module().isCorrespondingHit(r.cheatType()));

            tally(byModule, spec.module(), hit);
            // 来源 / 档位视图只在「已有对应检测项」的模块上统计检出率，
            // 未实现检测项的模块（PHASE / JESUS）单列，不计入三视图的检出率分母
            if (spec.module().hasDetection()) {
                tally(bySource, spec.source(), hit);
                tally(byTier, spec.tier(), hit);
            }

            if (spec.module().hasDetection() && !hit) {
                bypass.add(spec.source() + " / " + spec.tier() + " / " + spec.module());
            }
        }

        System.out.println("[T3] 检出率（按模块）：");
        for (Map.Entry<CheatModule, long[]> entry : byModule.entrySet()) {
            long[] stat = entry.getValue();
            System.out.printf("     %-13s 命中=%-3d 注入=%-3d 检出率=%.3f%s%n",
                    entry.getKey(), stat[1], stat[0], rate(stat), entry.getKey().hasDetection() ? "" : "（未实现检测项）");
        }
        System.out.println("[T3] 检出率（按来源，仅已实现检测项的模块）：");
        for (Map.Entry<CheatSource, long[]> entry : bySource.entrySet()) {
            long[] stat = entry.getValue();
            System.out.printf("     %-15s 命中=%-3d 注入=%-3d 检出率=%.3f%n",
                    entry.getKey(), stat[1], stat[0], rate(stat));
        }
        System.out.println("[T3] 检出率（按档位，仅已实现检测项的模块）：");
        for (Map.Entry<CheatTier, long[]> entry : byTier.entrySet()) {
            long[] stat = entry.getValue();
            System.out.printf("     %-8s 命中=%-3d 注入=%-3d 检出率=%.3f%n",
                    entry.getKey(), stat[1], stat[0], rate(stat));
        }

        List<String> unimplemented = new ArrayList<>();
        for (CheatModule module : CheatModule.values()) {
            if (!module.hasDetection()) {
                unimplemented.add(module.name());
            }
        }
        System.out.println("[T3] 已实现但被绕过清单：" + (bypass.isEmpty() ? "（空）" : "\n     " + String.join("\n     ", bypass)));
        System.out.println("[T3] 未实现检测项清单：" + (unimplemented.isEmpty() ? "（空）" : String.join(", ", unimplemented)));
        System.out.printf("[T3] 注入规格总数=%d 绕过数=%d%n", specs.size(), bypass.size());

        for (CheatModule module : CheatModule.values()) {
            if (!module.hasDetection()) {
                continue;
            }
            long[] stat = byModule.getOrDefault(module, new long[] {0, 0});
            assertTrue(stat[0] > 0, module + " 应有注入规格");
            assertTrue(rate(stat) >= MIN_DETECTION_RATE,
                    module + " 检出率 " + rate(stat) + " 低于阈值 " + MIN_DETECTION_RATE);
        }
        assertTrue(bypass.isEmpty(), "已实现检测项的模块不应被绕过：" + bypass);
    }

    private static <T> void tally(Map<T, long[]> map, T key, boolean hit) {
        long[] stat = map.computeIfAbsent(key, unused -> new long[2]);
        stat[0]++;
        if (hit) {
            stat[1]++;
        }
    }

    private static double rate(long[] stat) {
        return stat[0] == 0 ? 0.0 : (double) stat[1] / stat[0];
    }
}
