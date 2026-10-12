package com.potatotv.pacc.detection.local;

import com.potatotv.pacc.detection.fusion.Evidence;
import com.potatotv.pacc.detection.fusion.Layer;
import com.potatotv.pacc.detection.local.os.OsProbe;
import com.potatotv.pacc.detection.signature.SignatureLibrary;

import java.util.List;

/**
 * 玩家端本地检测器契约（技术文档 §4.2 local 包）。
 *
 * <p>实现要点：
 * <ul>
 *   <li>只消费 {@link LocalScanContext} 注入的本机数据，不得自行越界采集</li>
 *   <li>白名单优先（技术文档 §5.1 误报抑制列）：合法 Mod / 系统 DLL / 用户自用工具先排除</li>
 *   <li>单次扫描产出 0..n 条 {@link Evidence}，置信度 0-1</li>
 *   <li>任何异常都在实现内部吞掉并返回已收集证据，扫描失败不得影响其他检测器</li>
 * </ul>
 */
public interface LocalDetector {

    /** 检测器标识（融合结果里按它归因）。 */
    String id();

    /** 所属纵深层。 */
    Layer layer();

    /** 执行一次扫描。 */
    List<Evidence> scan(LocalScanContext ctx);
}
