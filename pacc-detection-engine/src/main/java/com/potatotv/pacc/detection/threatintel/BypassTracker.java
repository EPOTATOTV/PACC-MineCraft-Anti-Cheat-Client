package com.potatotv.pacc.detection.threatintel;

import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 绕过技术跟踪（技术文档 §九 反规避）。
 *
 * <p>记录作弊方针对某条反制措施使用的绕过手法，供运营复盘与反制升级。
 * 上限 {@value #MAX_EVENTS} 条，超限丢最旧，防止长期运行内存无界增长。
 */
public final class BypassTracker {

    /** 一次绕过记录。 */
    public record BypassEvent(String cheatName, String technique, String countermeasure, long timestampMs) {
    }

    private static final int MAX_EVENTS = 1000;

    private final Deque<BypassEvent> events = new ConcurrentLinkedDeque<>();

    /** 登记一条绕过记录。 */
    public void register(BypassEvent event) {
        events.addLast(event);
        // 并发下 while 微超上限无害，多出的会在下一次登记时被挤掉。
        while (events.size() > MAX_EVENTS) {
            events.pollFirst();
        }
    }

    /** 全部记录，最旧在前。 */
    public List<BypassEvent> records() {
        return List.copyOf(events);
    }

    /** timestampMs ≥ sinceEpochMs 的记录。 */
    public List<BypassEvent> recent(long sinceEpochMs) {
        return events.stream().filter(event -> event.timestampMs() >= sinceEpochMs).toList();
    }
}
