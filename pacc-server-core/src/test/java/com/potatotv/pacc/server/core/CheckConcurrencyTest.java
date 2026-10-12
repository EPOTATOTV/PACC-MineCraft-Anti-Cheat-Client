package com.potatotv.pacc.server.core;

import com.potatotv.pacc.server.core.check.CombatCheck;
import com.potatotv.pacc.server.core.check.InteractionCheck;
import com.potatotv.pacc.server.core.check.InventoryCheck;
import com.potatotv.pacc.server.core.check.MoveCheck;
import com.potatotv.pacc.server.core.model.CheckType;
import com.potatotv.pacc.server.core.model.CombatEvent;
import com.potatotv.pacc.server.core.model.DetectionResult;
import com.potatotv.pacc.server.core.model.GameMode;
import com.potatotv.pacc.server.core.model.PlayerPosition;
import com.potatotv.pacc.server.core.model.Position;
import com.potatotv.pacc.server.core.model.ServerPluginConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 有状态检查项的并发安全测试。
 *
 * <p>服务端插件是多线程环境：同一检查实例会被多个玩家线程并发调用，内部用
 * {@link java.util.concurrent.ConcurrentHashMap} 维护「每玩家」状态。这里的核心断言是
 * <b>状态隔离</b>——某玩家在单线程内的判定序列不因其它玩家的并发调用而改变；同时验证
 * 干净输入在任何并发时序下都不产生违规。
 *
 * <p>所有断言都是确定性的：固定输入 + 固定时间戳，不依赖线程调度顺序，也不 sleep 等时序。
 */
class CheckConcurrencyTest {

    private static final int THREADS = 8;
    private static final int PER_THREAD = 500;

    @Test
    void cleanMovesStayCleanUnderConcurrentCallers() {
        MoveCheck check = new MoveCheck(ServerPluginConfig.defaults());
        runConcurrently(THREADS, t -> {
            String playerId = "mover-" + t;
            long base = 1_000_000L + t * 1_000_000L;
            for (int i = 0; i < PER_THREAD; i++) {
                // 每刻水平位移 0.3 块（< 阈值 0.42*1.5），落地：任何时序下都应干净
                List<DetectionResult> results = check.onMove(
                        pos(playerId, 0, 64, 0, base + i * 50L, true),
                        pos(playerId, 0.3, 64, 0, base + i * 50L + 50L, true));
                assertNoViolation(results, "干净移动");
            }
        });
    }

    @Test
    void airborneStreakIsCountedPerPlayer() {
        MoveCheck check = new MoveCheck(ServerPluginConfig.defaults());
        // 每玩家 25 次滞空上升：滞空计数达到 20 前不触发，之后触发「连续滞空」
        AtomicIntegerArray flyCounts = new AtomicIntegerArray(THREADS);
        runConcurrently(THREADS, t -> {
            String playerId = "flyer-" + t;
            long base = 5_000_000L + t * 1_000_000L;
            for (int i = 0; i < 25; i++) {
                // 每刻上升 0.4 块，不超过 vClimb 阈值 0.42，唯一致因是滞空计数
                List<DetectionResult> results = check.onMove(
                        pos(playerId, 0, 64 + i * 0.4, 0, base + i * 50L, false),
                        pos(playerId, 0, 64 + (i + 1) * 0.4, 0, base + i * 50L + 50L, false));
                boolean flagged = hasCheat(results, CheckType.MOVE, "FLY");
                if (i < 19) {
                    assertTrue(!flagged, "第 " + i + " 次不应触发滞空，其它玩家不得污染计数：" + results);
                } else {
                    assertTrue(flagged, "第 " + i + " 次应触发滞空，计数被并发了：" + results);
                }
                if (flagged) {
                    flyCounts.incrementAndGet(t);
                }
            }
        });
        for (int t = 0; t < THREADS; t++) {
            assertEquals(6, flyCounts.get(t),
                    "玩家 flyer-" + t + " 的滞空触发次数应恒为 6（25-19），实际 " + flyCounts.get(t));
        }
    }

    @Test
    void combatCountersArePerPlayerUnderConcurrency() {
        CombatCheck check = new CombatCheck(ServerPluginConfig.defaults());
        runConcurrently(THREADS, t -> {
            String attacker = "attacker-" + t;
            long base = 2_000_000L + t * 2_000_000L;
            for (int i = 0; i < PER_THREAD; i++) {
                // 200ms 一次共 5 CPS（< 16），距离 1.0（< 3.1），目标固定，视角 0 度
                CombatEvent event = new CombatEvent(attacker, "victim",
                        new Position(0, 64, 0), new Position(1, 64, 0),
                        base + i * 200L, false, 0.0);
                assertNoViolation(check.onCombat(event), "正常点击");
            }
        });
    }

    @Test
    void placePerTickCountsAreIsolatedPerPlayer() {
        InteractionCheck check = new InteractionCheck(ServerPluginConfig.defaults());
        // 所有线程使用同一时间基准：同一刻每个玩家各自只放置一次。
        // 若「每刻放置计数」被跨玩家共享，计数会被顶穿阈值而报 FAST_PLACE。
        runConcurrently(THREADS, t -> {
            String playerId = "builder-" + t;
            long base = 3_000_000L;
            for (int i = 0; i < PER_THREAD; i++) {
                List<DetectionResult> results =
                        check.onInteract(playerId, null, "PLACE", base + i * 50L);
                assertNoViolation(results, "同刻单次放置");
            }
        });
    }

    @Test
    void inventoryIntervalsAreTrackedPerPlayer() {
        InventoryCheck check = new InventoryCheck();
        runConcurrently(THREADS, t -> {
            String playerId = "eater-" + t;
            long base = 4_000_000L + t * 1_000_000L;
            for (int i = 0; i < PER_THREAD; i++) {
                // 间隔 1s（> 800ms 阈值），即便并发也不应被判 FAST_EAT
                List<DetectionResult> results =
                        check.onInventory(playerId, "FAST_EAT", base + i * 1000L);
                assertNoViolation(results, "正常进食间隔");
            }
        });
    }

    @Test
    void resetClearsPerPlayerState() {
        ServerPluginConfig config = ServerPluginConfig.defaults();
        MoveCheck move = new MoveCheck(config);
        CombatCheck combat = new CombatCheck(config);
        InteractionCheck interaction = new InteractionCheck(config);
        InventoryCheck inventory = new InventoryCheck();

        // MoveCheck：先把滞空计数堆到触发阈值，reset 后应从零重新计数
        for (int i = 0; i < 25; i++) {
            move.onMove(pos("resetter", 0, 64 + i * 0.4, 0, i * 50L, false),
                    pos("resetter", 0, 64 + (i + 1) * 0.4, 0, i * 50L + 50L, false));
        }
        move.reset("resetter");
        assertTrue(move.onMove(pos("resetter", 0, 64, 0, 1000, false),
                pos("resetter", 0, 64.4, 0, 1050, false)).isEmpty(),
                "reset 后滞空计数应清零，首次滞空不得触发");

        // InteractionCheck：reset 后同一刻的放置计数应重新开始
        assertTrue(interaction.onInteract("r-interact", null, "PLACE", 1000).isEmpty());
        interaction.reset("r-interact");
        assertTrue(interaction.onInteract("r-interact", null, "PLACE", 1000).isEmpty(),
                "reset 后同一刻的计数应被清空");

        // InventoryCheck：reset 后上一次进食时间戳应被清除
        inventory.onInventory("r-inventory", "FAST_EAT", 1000);
        inventory.reset("r-inventory");
        assertTrue(inventory.onInventory("r-inventory", "FAST_EAT", 1100).isEmpty(),
                "reset 后短间隔不应沿用旧时间戳");

        // CombatCheck：把点击计数堆到 AUTO_CLICKER 阈值，reset 后点击窗口应清零
        for (int i = 0; i < 17; i++) {
            combat.onCombat(new CombatEvent("r-combat", "victim",
                    new Position(0, 64, 0), new Position(1, 64, 0), 1000 + i * 10L, false, 0.0));
        }
        combat.reset("r-combat");
        assertTrue(combat.onCombat(new CombatEvent("r-combat", "victim",
                new Position(0, 64, 0), new Position(1, 64, 0), 2000, false, 0.0)).isEmpty(),
                "reset 后点击窗口应清零");
    }

    /**
     * 起 {@code threadCount} 个线程，全部就绪后同时开跑，各执行 {@code task}（入参为线程序号）。
     * 任一任务抛异常都会在结束后重新抛出，保证失败不被吞掉。
     */
    private static void runConcurrently(int threadCount, IntConsumer task) {
        ExecutorService pool = Executors.newFixedThreadPool(threadCount);
        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        try {
            for (int t = 0; t < threadCount; t++) {
                int index = t;
                pool.execute(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        task.accept(index);
                    } catch (Throwable ex) {
                        failure.compareAndSet(null, ex);
                    } finally {
                        done.countDown();
                    }
                });
            }
            assertTrue(ready.await(30, TimeUnit.SECONDS), "线程未在超时前就绪");
            start.countDown();
            assertTrue(done.await(60, TimeUnit.SECONDS), "并发任务未在超时前完成");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            fail("并发测试被中断");
        } finally {
            pool.shutdownNow();
        }
        if (failure.get() != null) {
            throw new AssertionError("并发调用抛出异常", failure.get());
        }
    }

    private static void assertNoViolation(List<DetectionResult> results, String scenario) {
        assertTrue(results.isEmpty(), scenario + "不应产生违规，实际：" + results);
    }

    private static PlayerPosition pos(
            String id, double x, double y, double z, long ts, boolean onGround) {
        return new PlayerPosition(id, new Position(x, y, z), 0f, 0f, ts, onGround, GameMode.SURVIVAL);
    }

    private static boolean hasCheat(List<DetectionResult> results, CheckType type, String cheatType) {
        return results.stream().anyMatch(r -> r.checkType() == type && cheatType.equals(r.cheatType()));
    }
}
