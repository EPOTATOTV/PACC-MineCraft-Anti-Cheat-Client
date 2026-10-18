package com.potatotv.pacc.detection.threatintel;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 社区签名上报（技术文档 §4.2 threatintel 包，指纹库每周更新的社区通道）。
 *
 * <p>上报先进 pending 队列，复核后经 {@link #mergeInto} 一次性并入签名库。
 * 去重规则：同 id、或与既有条目完全相同特征（id 之外全字段一致）的提交直接忽略。
 */
public final class CommunitySignatures {

    private final List<CheatFingerprint> pending = new CopyOnWriteArrayList<>();

    /** 提交一条社区签名。 */
    public void submit(CheatFingerprint fingerprint) {
        // CheatFingerprint 构造器已保证 riskLevel 1-5，这里对公开入口再拦一次，
        // 防止未来放宽 record 校验后脏数据进队列。
        if (fingerprint.riskLevel() < 1 || fingerprint.riskLevel() > 5) {
            throw new IllegalArgumentException("riskLevel 必须在 1-5: " + fingerprint.id());
        }
        boolean duplicate = pending.stream()
                .anyMatch(existing -> existing.id().equals(fingerprint.id()) || sameSignature(existing, fingerprint));
        if (!duplicate) {
            pending.add(fingerprint);
        }
    }

    /** 待合并的社区签名快照。 */
    public List<CheatFingerprint> pending() {
        return List.copyOf(pending);
    }

    /** 待合并数量。 */
    public int pendingCount() {
        return pending.size();
    }

    /**
     * 把当前 pending 全部注册进签名库并清空队列；合并期间新提交的条目保留在队列里。
     *
     * <p>与库内已有 id 冲突的条目直接丢弃，不覆盖内置 / 已发布指纹：
     * 社区上报不能改掉官方条目的匹配规则，否则一次错误提交就能让整条检测失效。
     */
    public synchronized void mergeInto(SignatureLibrary library) {
        if (pending.isEmpty()) {
            return;
        }
        List<CheatFingerprint> snapshot = List.copyOf(pending);
        java.util.Set<String> existingIds = library.fingerprints().stream()
                .map(CheatFingerprint::id)
                .collect(java.util.stream.Collectors.toSet());
        List<CheatFingerprint> fresh = snapshot.stream()
                .filter(fingerprint -> !existingIds.contains(fingerprint.id()))
                .toList();
        library.registerAll(fresh);
        // 冲突条目也从队列移除：它们永远不会被合并，留着只会反复触发同一轮检查
        pending.removeAll(snapshot);
    }

    /** 特征级相等（不含 id）：同特征不同 id 也算重复上报。 */
    private static boolean sameSignature(CheatFingerprint a, CheatFingerprint b) {
        return a.type() == b.type()
                && a.name().equals(b.name())
                && a.category() == b.category()
                && a.riskLevel() == b.riskLevel()
                && a.processNames().equals(b.processNames())
                && a.windowClasses().equals(b.windowClasses())
                && a.windowTitles().equals(b.windowTitles())
                && a.moduleNames().equals(b.moduleNames())
                && a.fileHashes().equals(b.fileHashes())
                && a.memoryPatterns().equals(b.memoryPatterns());
    }
}
