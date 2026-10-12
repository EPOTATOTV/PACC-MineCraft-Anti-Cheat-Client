package com.potatotv.pacc.detection.fusion;

import com.potatotv.pacc.detection.signature.CheatFingerprint;
import com.potatotv.pacc.detection.signature.FingerprintCategory;
import com.potatotv.pacc.detection.signature.FingerprintMatch;
import com.potatotv.pacc.detection.signature.FingerprintType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * {@link Evidence} 的日志注入防护测试。
 *
 * <p>detail / matchedValue 可能直接来自探针读到的进程名、窗口标题、文件名，属外部可控数据；
 * 换行与控制字符不清掉就能在服务端日志里伪造行。
 */
class EvidenceTest {

    @Test
    void null归一为空串() {
        assertEquals("", Evidence.sanitize(null), "探针读不到值时是 null，不能抛 NPE");
    }

    @Test
    void 剥离换行与其余控制字符() {
        assertEquals("wurstclient7", Evidence.sanitize("wurst\nclient\r\t7"),
                "CR/LF/TAB 必须清掉，否则可伪造日志行");
        assertEquals("abc", Evidence.sanitize("a\u0000b\u001Fc"), "NUL 与 C0 控制字符一并清掉");
        assertEquals("ab", Evidence.sanitize("a\u007Fb"), "DEL 也要清掉");
    }

    @Test
    void 正常文本原样保留() {
        assertEquals("Cheat Engine 主窗口", Evidence.sanitize("Cheat Engine 主窗口"));
        assertEquals("CHEATENGINE-X86_64.EXE", Evidence.sanitize("CHEATENGINE-X86_64.EXE"));
    }

    @Test
    void 超长截断到上限() {
        String longText = "x".repeat(5000);
        assertEquals(4096, Evidence.sanitize(longText).length(), "超长外部数据不得整段进证据链");
    }

    @Test
    void 构造证据时同样净化detail() {
        Evidence evidence = Evidence.of("process-scanner", Layer.PROCESS, 0.8, "命中\r\n伪造行");
        assertEquals("命中伪造行", evidence.detail());
        assertFalse(evidence.detail().contains("\n"));
    }

    @Test
    void 指纹命中的matchedValue同样净化() {
        CheatFingerprint fingerprint = new CheatFingerprint(
                "cheat-engine", FingerprintType.PROCESS, "Cheat Engine",
                List.of("cheatengine"), List.of(), List.of(), List.of(), List.of(), List.of(),
                FingerprintCategory.MEMORY_TOOL, 5);
        FingerprintMatch match = new FingerprintMatch(
                fingerprint, FingerprintType.PROCESS, "cheat\nengine", 0.8);
        assertEquals("cheatengine", match.matchedValue());
    }
}
