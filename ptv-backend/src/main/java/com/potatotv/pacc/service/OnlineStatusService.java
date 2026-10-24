package com.potatotv.pacc.service;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 玩家端 WebSocket 长连接注册表（在线玩家列表）。
 * 红屏广播基于此集合实现全在线并行推送。
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings("null") // WebSocket 会话集合流式操作的 null 分析误报
public class OnlineStatusService {

    private static final Logger log = LoggerFactory.getLogger(OnlineStatusService.class);

    /** pteid -> 会话集合（同一账号多设备）。 */
    private final Map<String, CopyOnWriteArraySet<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    private final SessionStateService sessionState;

    /**
     * 在线人数。Redis 可用时按跨实例去重后的 PTEID 计数；
     * Redis 不可用或读取失败时回退到本实例会话数。
     */
    public int onlineCount() {
        long shared = sessionState.onlineCount();
        if (shared >= 0L) {
            return (int) Math.min(Integer.MAX_VALUE, shared);
        }
        return sessions.values().stream().mapToInt(CopyOnWriteArraySet::size).sum();
    }

    public void register(String pteid, WebSocketSession session) {
        sessions.computeIfAbsent(pteid, k -> new CopyOnWriteArraySet<>()).add(session);
        sessionState.heartbeat(pteid);
    }

    public void unregister(String pteid, WebSocketSession session) {
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(pteid);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) {
                sessions.remove(pteid);
                sessionState.offline(pteid);
            }
        }
    }

    /** 收到任意玩家消息时刷新跨实例在线心跳。 */
    public void heartbeat(String pteid) {
        sessionState.heartbeat(pteid);
    }

    /**
     * 本地会话心跳兜底：客户端只发 WebSocket PING（容器处理，不经过业务 handler）时，
     * 靠这个定时任务维持 Redis 里的在线状态。
     */
    @Scheduled(fixedDelayString = "${pacc.session.heartbeat-interval-ms:60000}")
    public void refreshPresence() {
        if (!sessionState.enabled()) {
            return;
        }
        for (String pteid : sessions.keySet()) {
            sessionState.heartbeat(pteid);
        }
    }

    /** 返回指定 PTEID 的任一在线会话（用于定向下发）。 */
    public java.util.Optional<WebSocketSession> firstSession(String pteid) {
        CopyOnWriteArraySet<WebSocketSession> set = sessions.get(pteid);
        if (set == null || set.isEmpty()) return java.util.Optional.empty();
        return set.stream().filter(WebSocketSession::isOpen).findFirst();
    }

    /** 向单个会话发送文本（定向通知）。 */
    public void broadcastTo(WebSocketSession session, String payload) {
        if (session == null || !session.isOpen()) return;
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(payload));
            }
        } catch (IOException ex) {
            log.warn("定向发送失败 session={} err={}", session.getId(), ex.getMessage());
        }
    }

    /** 广播给所有在线玩家会话。返回成功送达数。 */
    public long broadcast(String payload) {
        long ack = 0;
        for (Map.Entry<String, CopyOnWriteArraySet<WebSocketSession>> e : sessions.entrySet()) {
            for (WebSocketSession s : e.getValue()) {
                try {
                    if (s.isOpen()) {
                        synchronized (s) {
                            s.sendMessage(new TextMessage(payload));
                        }
                        ack++;
                    }
                } catch (IOException ex) {
                    log.warn("广播失败 session={} pteid={} err={}", s.getId(), e.getKey(), ex.getMessage());
                }
            }
        }
        return ack;
    }
}