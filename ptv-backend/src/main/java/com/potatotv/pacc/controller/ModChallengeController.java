package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.security.ModChallengeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 玩家端 Mod 清单质询（R-09）。客户端拿到质询后，把 challengeId / nonce 与 Mod 清单摘要
 * 一起签名，随 {@code pacc:mods} v2 报文上报，由服务端插件转发到
 * {@code /api/v1/server/mod-verify} 核验。
 */
@RestController
@RequestMapping("/api/player")
@RequiredArgsConstructor
public class ModChallengeController {

    private final ModChallengeService modChallengeService;

    @PostMapping("/mod-challenge")
    public ResponseEntity<?> issue(HttpServletRequest req) {
        Object pteid = req.getAttribute("pteid");
        if (pteid == null || pteid.toString().isBlank()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "未登录"));
        }
        return ResponseEntity.ok(modChallengeService.issue(pteid.toString()));
    }
}
