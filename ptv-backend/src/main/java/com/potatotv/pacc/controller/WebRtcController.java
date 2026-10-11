package com.potatotv.pacc.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 远程查端 WebRTC 接入参数：向管理端下发 ICE（STUN/TURN）服务器列表。
 * <p>凭据一律来自配置/环境变量（{@code pacc.webrtc.*}），不硬编码；未配置 TURN 时
 * 仅返回 STUN 列表并标记 {@code turn_configured=false}，由前端提示企业 NAT/防火墙下可能连不通。</p>
 */
@RestController
@RequestMapping("/api/admin/webrtc")
public class WebRtcController {

    private final List<String> stunUrls;
    private final List<String> turnUrls;
    private final String turnUsername;
    private final String turnCredential;

    public WebRtcController(
            @Value("${pacc.webrtc.stun-urls:stun:stun.l.google.com:19302}") String stunUrls,
            @Value("${pacc.webrtc.turn-urls:}") String turnUrls,
            @Value("${pacc.webrtc.turn-username:}") String turnUsername,
            @Value("${pacc.webrtc.turn-credential:}") String turnCredential) {
        this.stunUrls = split(stunUrls);
        this.turnUrls = split(turnUrls);
        this.turnUsername = turnUsername == null ? "" : turnUsername.trim();
        this.turnCredential = turnCredential == null ? "" : turnCredential.trim();
    }

    @GetMapping("/ice-servers")
    public Map<String, Object> iceServers() {
        List<Map<String, Object>> servers = new ArrayList<>();
        if (!stunUrls.isEmpty()) {
            Map<String, Object> stun = new LinkedHashMap<>();
            stun.put("urls", stunUrls);
            servers.add(stun);
        }
        boolean turnConfigured = !turnUrls.isEmpty();
        if (turnConfigured) {
            Map<String, Object> turn = new LinkedHashMap<>();
            turn.put("urls", turnUrls);
            if (!turnUsername.isBlank()) turn.put("username", turnUsername);
            if (!turnCredential.isBlank()) turn.put("credential", turnCredential);
            servers.add(turn);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ice_servers", servers);
        out.put("turn_configured", turnConfigured);
        return out;
    }

    private static List<String> split(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String s : raw.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}