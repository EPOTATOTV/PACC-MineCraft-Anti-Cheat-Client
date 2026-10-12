package com.potatotv.pacc.detection.threatintel;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 绕过技术跟踪测试：登记顺序、时间过滤、上限淘汰、并发安全。
 */
class BypassTrackerTest {

    private static BypassTracker.BypassEvent event(String cheat, long ts) {
        return new BypassTracker.BypassEvent(cheat, "更换注入手法", "内存特征码更新", ts);
    }

    @Test
    void 登记与全量记录最旧在前() {
        BypassTracker tracker = new BypassTracker();
        tracker.register(event("vape v4", 1000));
        tracker.register(event("cheat engine", 2000));
        assertEquals(2, tracker.records().size());
        assertEquals("vape v4", tracker.records().get(0).cheatName());
        assertEquals("cheat engine", tracker.records().get(1).cheatName());
    }

    @Test
    void recent按时间过滤() {
        BypassTracker tracker = new BypassTracker();
        tracker.register(event("a", 1000));
        tracker.register(event("b", 2000));
        tracker.register(event("c", 3000));
        assertEquals(2, tracker.recent(2000).size());
        assertEquals(1, tracker.recent(3000).size());
        assertTrue(tracker.recent(3001).isEmpty());
    }

    @Test
    void 超过上限丢最旧() {
        BypassTracker tracker = new BypassTracker();
        for (int i = 0; i < 1010; i++) {
            tracker.register(event("c" + i, i));
        }
        assertEquals(1000, tracker.records().size());
        assertEquals(10, tracker.records().get(0).timestampMs());
        assertEquals(1009, tracker.records().get(999).timestampMs());
    }

    @Test
    void 并发登记不丢数据() throws Exception {
        BypassTracker tracker = new BypassTracker();
        int threads = 2;
        int perThread = 300;
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            final int base = t * perThread;
            new Thread(() -> {
                for (int i = 0; i < perThread; i++) {
                    tracker.register(event("c" + (base + i), base + i));
                }
                done.countDown();
            }).start();
        }
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertEquals(threads * perThread, tracker.records().size());
    }
}
