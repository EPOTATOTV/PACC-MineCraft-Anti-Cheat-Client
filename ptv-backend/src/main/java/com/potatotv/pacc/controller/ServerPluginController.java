package com.potatotv.pacc.controller;

import com.potatotv.pacc.service.ServerPluginService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 服务端插件通道：注册 / 检测事件上报 / 处置留痕 / 信誉查询 / 配置下发。
 *
 * <p>鉴权由 {@code ServerPluginAuthFilter} 用共享密钥校验请求体签名（{@code X-PACC-Signature}）；
 * 这些接口不经过开放 API 的 Key + 时间窗机制。响应只含受理结果与聚合信息。</p>
 */
@RestController
@RequestMapping("/api/v1/server")
@RequiredArgsConstructor
public class ServerPluginController {

    private final ServerPluginService service;

    @PostMapping("/register")
    public Map<String, Object> register(@RequestBody ServerPluginService.RegisterRequest req) {
        service.register(req);
        return Map.of("ok", true);
    }

    @PostMapping("/event")
    public ServerPluginService.EventAck event(@RequestBody ServerPluginService.EventRequest req) {
        return service.recordEvent(req);
    }

    @PostMapping("/punishment")
    public Map<String, Object> punishment(@RequestBody ServerPluginService.PunishmentRequest req) {
        service.recordPunishment(req);
        return Map.of("ok", true);
    }

    @GetMapping("/reputation")
    public ServerPluginService.ReputationResponse reputation(
            @RequestParam(required = false) String pteid,
            @RequestParam(required = false) String playerUuid) {
        return service.reputation(pteid, playerUuid);
    }

    @GetMapping("/config")
    public ServerPluginService.ConfigResponse config() {
        return service.config();
    }
}
