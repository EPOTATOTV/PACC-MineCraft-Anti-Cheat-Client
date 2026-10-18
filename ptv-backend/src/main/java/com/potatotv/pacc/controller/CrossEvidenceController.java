package com.potatotv.pacc.controller;

import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.CrossEvidenceChain;
import com.potatotv.pacc.service.CrossEvidenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 管理端跨端证据链（P-06）：同一 PTEID 的玩家端检测事件 + 服务端插件事件。
 * 只读，不修改任何数据；权限沿用玩家查询的 {@code players:read}。
 */
@RestController
@RequestMapping("/api/admin/cross-evidence")
@RequiredArgsConstructor
public class CrossEvidenceController {

    private final CrossEvidenceService crossEvidenceService;

    @GetMapping("/players/{pteid}")
    @RequirePermission("players:read")
    public CrossEvidenceChain playerChain(@PathVariable String pteid) {
        String key = pteid == null ? "" : pteid.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PTEID 不合法");
        }
        return crossEvidenceService.chain(key);
    }
}
