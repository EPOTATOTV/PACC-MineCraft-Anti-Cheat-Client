package com.potatotv.pacc.controller;

import com.potatotv.pacc.domain.CrossEvidenceArchive;
import com.potatotv.pacc.security.RequirePermission;
import com.potatotv.pacc.service.CrossEvidenceArchiveService;
import com.potatotv.pacc.service.CrossEvidenceChain;
import com.potatotv.pacc.service.CrossEvidenceService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 管理端跨端证据链（P-06 / R-05）：同一 PTEID 的玩家端检测事件 + 服务端插件事件。
 * 查询只读；归档接口把当前快照固化到 t_cross_evidence_archive。
 */
@RestController
@RequestMapping("/api/admin/cross-evidence")
@RequiredArgsConstructor
public class CrossEvidenceController {

    private final CrossEvidenceService crossEvidenceService;
    private final CrossEvidenceArchiveService archiveService;

    @GetMapping("/players/{pteid}")
    @RequirePermission("players:read")
    public CrossEvidenceChain playerChain(@PathVariable String pteid) {
        String key = pteid == null ? "" : pteid.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PTEID 不合法");
        }
        return crossEvidenceService.chain(key);
    }

    /** 归档当前证据链快照；同一 PTEID 并发归档只允许一个实例写入。 */
    @PostMapping("/players/{pteid}/archive")
    @RequirePermission("players:update")
    public ResponseEntity<?> archive(@PathVariable String pteid, HttpServletRequest req) {
        String key = pteid == null ? "" : pteid.trim();
        if (key.isEmpty() || key.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PTEID 不合法");
        }
        Object actor = req.getAttribute("adminActor");
        try {
            CrossEvidenceArchive saved = archiveService.archive(key, actor == null ? "admin" : actor.toString());
            return ResponseEntity.ok(Map.of(
                    "id", saved.getId(),
                    "pteid", saved.getPteid(),
                    "sha256", saved.getSha256(),
                    "created_at", saved.getCreatedAt().toString()));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            // 归档锁被占用：返回 409，前端可提示稍后重试
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }
}
