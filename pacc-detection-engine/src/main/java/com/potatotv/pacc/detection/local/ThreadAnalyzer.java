package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.ThreadInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * L2 线程注入痕迹检测（技术文档 &sect;6.2 远程线程 / shellcode 注入）。
 *
 * <p>线程起始地址不落在任何已映射模块内，是 shellcode 注入的典型痕迹。
 * 同一线程 ID 只报一次，避免长期驻留的注入线程每轮刷屏。
 */
public final class ThreadAnalyzer implements LocalDetector {

    private static final System.Logger LOG = System.getLogger(ThreadAnalyzer.class.getName());

    private static final double SHELLCODE_CONFIDENCE = 0.9;

    /** 已上报的线程 ID。 */
    private final Set<Long> reportedThreads = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "thread-analyzer";
    }

    @Override
    public Layer layer() {
        return Layer.MODULE;
    }

    @Override
    public List<Evidence> scan(LocalScanContext ctx) {
        List<Evidence> out = new ArrayList<>();
        try {
            if (ctx.gamePid() <= 0) {
                return out;
            }
            for (ThreadInfo thread : ctx.probe().threads(ctx.gamePid())) {
                if (thread.startInMappedImage()) {
                    continue;
                }
                if (!reportedThreads.add(thread.threadId())) {
                    continue;
                }
                boolean hasModule = thread.startModuleName() != null && !thread.startModuleName().isBlank();
                String startModule = hasModule ? thread.startModuleName() : "未映射区域";
                out.add(Evidence.of(id(), Layer.MODULE, SHELLCODE_CONFIDENCE,
                        "线程起始地址不在已映射模块内（疑似 shellcode 注入）: thread="
                                + thread.threadId() + " startModule=" + startModule));
            }
        } catch (RuntimeException e) {
            LOG.log(System.Logger.Level.WARNING, "线程分析失败", e);
        }
        return out;
    }
}
