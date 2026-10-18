package com.potatotv.paccclient.probe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * {@link KernelBridge} 离线单测（F-15 / F-16）。
 *
 * <p>用一个返回固化字节流的假 {@link KernelTransport} 驱动，覆盖正常解码、字段边界、
 * 截断 / 长度不符、设备缺失 / 异常、分页累加，以及结构体偏移与 {@code pacc_common.h}
 * 的一致性。不依赖真实驱动，也不发起任何系统调用。</p>
 */
class KernelBridgeTest {

    // ========================================================================================
    // 契约常量：与 platform/kernel-windows/pacc_common.h 对齐
    // ========================================================================================

    @Test
    void 控制码与结构体尺寸对齐驱动头文件() {
        // CTL_CODE(0x22, 0x8000+n, METHOD_BUFFERED, FILE_ANY_ACCESS)：
        // (0x22 << 16) | ((0x8000+n) << 2)，注意第二项自带 bit17，与 0x22 的高位重叠，不能当加法
        assertEquals(0x220028, KernelBridge.IOCTL_QUERY_SSDT_STATUS, "IOCTL_PACC_QUERY_SSDT_STATUS = +10");
        assertEquals(0x22002C, KernelBridge.IOCTL_QUERY_DRIVER_STATUS, "IOCTL_PACC_QUERY_DRIVER_STATUS = +11");
        assertEquals(0x220038, KernelBridge.IOCTL_QUERY_IDT_STATUS, "IOCTL_PACC_QUERY_IDT_STATUS = +14");
        assertEquals(0x22003C, KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, "IOCTL_PACC_QUERY_CALLBACK_STATUS = +15");
        assertEquals(80, KernelBridge.SSDT_STATUS_SIZE, "PACC_SSDT_STATUS 尺寸");
        assertEquals(48, KernelBridge.IDT_STATUS_SIZE, "PACC_IDT_STATUS 尺寸");
        assertEquals(1048, KernelBridge.CALLBACK_CLASS_SIZE, "PACC_CALLBACK_CLASS 尺寸");
        assertEquals(4208, KernelBridge.CALLBACK_REPORT_SIZE, "PACC_CALLBACK_REPORT 尺寸");
        assertEquals(16, KernelBridge.DRIVER_REPORT_HEADER_SIZE, "PACC_DRIVER_REPORT 头部尺寸");
        assertEquals(1056, KernelBridge.DRIVER_ENTRY_SIZE, "PACC_DRIVER_ENTRY 尺寸");
        assertEquals(0x00000002, KernelBridge.DRIVER_FLAG_UNSIGNED, "PACC_DRIVER_FLAG_UNSIGNED");
    }

    // ========================================================================================
    // 正常解码
    // ========================================================================================

    @Test
    void 正常响应解码出SSDT_IDT_未签名驱动与回调计数() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 3));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS,
                driverPage(2, 2, 2, 0x2 /* 未签名 */, 0x1 /* 已签名 */));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 2));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 12, 4));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(3, state.ssdtHooks());
        assertEquals(2, state.idtHooks(), "AnomalyCount 即 IDT 异常条目数");
        assertEquals(1, state.unsignedDriverCount());
        assertEquals(4, state.suspiciousCallbackCount(), "Report.Suspicious 即第三方回调数");
        assertNotNull(state.note());
        assertTrue(state.note().contains("未签名驱动 1"));
        assertTrue(state.note().contains("IDT 异常 2/256"));
        assertTrue(state.note().contains("可疑内核回调 4"), "note 应含真实回调数");
        assertTrue(state.note().contains("0x3"), "note 应含已枚举类别掩码");
        // 驱动枚举请求：StartIndex=0, MaxCount=64
        assertEquals(8, transport.lastDriverRequest.length);
        ByteBuffer req = ByteBuffer.wrap(transport.lastDriverRequest).order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(0, req.getInt(0));
        assertEquals(64, req.getInt(4));
    }

    @Test
    void SSDT快照不可用时仍接入但note标注() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(false, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(0, state.ssdtHooks());
        assertEquals(0, state.unsignedDriverCount());
        assertTrue(state.note().contains("SSDT 快照不可用"));
    }

    // ========================================================================================
    // 字段边界
    // ========================================================================================

    @Test
    void 全零响应解码为零值() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(0, state.ssdtHooks());
        assertEquals(0, state.idtHooks());
        assertEquals(0, state.unsignedDriverCount());
        assertEquals(0, state.suspiciousCallbackCount());
    }

    @Test
    void ULONG最大值饱和到Integer上限而非溢出为负() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0xFFFFFFFFL));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0xFFFFFFFFL));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0xFFFFFFFFL));

        KernelState state = new KernelBridge(transport).state();

        assertEquals(Integer.MAX_VALUE, state.ssdtHooks());
        assertEquals(Integer.MAX_VALUE, state.idtHooks());
        assertEquals(Integer.MAX_VALUE, state.suspiciousCallbackCount());
    }

    // ========================================================================================
    // IDT 完整性（+14）
    // ========================================================================================

    @Test
    void IDT异常数解析为idtHooks() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 240, 7));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(7, state.idtHooks());
        assertTrue(state.note().contains("IDT 异常 7/240"));
    }

    @Test
    void IDT驱动自述读取失败时保持零且note标注不可用() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(false, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported(), "IDT 不可用不应拖垮整条通道");
        assertEquals(0, state.idtHooks(), "未知不能冒充「无异常」");
        assertTrue(state.note().contains("IDT 完整性不可用"));
    }

    @Test
    void IDT响应长度不足时保持零且note标注不可用() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, new byte[KernelBridge.IDT_STATUS_SIZE - 1]);
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(0, state.idtHooks());
        assertTrue(state.note().contains("IDT 完整性不可用"));
    }

    // ========================================================================================
    // 内核回调枚举（+15）
    // ========================================================================================

    @Test
    void 回调报告Suspicious解析为可疑回调数() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x3, 30, 5));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(5, state.suspiciousCallbackCount());
        assertTrue(state.note().contains("可疑内核回调 5"));
    }

    @Test
    void 无类别可枚举时保持零且note标注不可用() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0));
        // ClassSupported=0：两类回调都没定位到，Suspicious 的 0 无意义
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS, callbackPage(0x0, 0, 0));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported(), "回调枚举不可用不应拖垮整条通道");
        assertEquals(0, state.suspiciousCallbackCount(), "未知不能冒充「无回调」");
        assertTrue(state.note().contains("内核回调枚举不可用"));
    }

    @Test
    void 回调报告长度不足时保持零且note标注不可用() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_IDT_STATUS, idt(true, 256, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS,
                new byte[KernelBridge.CALLBACK_REPORT_SIZE - 1]);

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(0, state.suspiciousCallbackCount());
        assertTrue(state.note().contains("内核回调枚举不可用"));
    }

    @Test
    void 旧驱动缺少IDT与回调IOCTL时降级不伪造零() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        // +14 / +15 未注册：假传输返回 null，模拟老版本驱动
        transport.responses.remove(KernelBridge.IOCTL_QUERY_IDT_STATUS);
        transport.responses.remove(KernelBridge.IOCTL_QUERY_CALLBACK_STATUS);

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(0, state.idtHooks());
        assertEquals(0, state.suspiciousCallbackCount());
        assertTrue(state.note().contains("IDT 完整性不可用"));
        assertTrue(state.note().contains("内核回调枚举不可用"));
    }

    // ========================================================================================
    // 截断 / 长度不符（契约无魔数，长度即有效性判据）
    // ========================================================================================

    @Test
    void 响应无魔数_以长度校验有效性() {
        FakeTransport shortResp = new FakeTransport();
        shortResp.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, new byte[KernelBridge.SSDT_STATUS_SIZE - 1]);
        assertFalse(new KernelBridge(shortResp).state().supported(), "少一字节即视为非法");

        FakeTransport exact = new FakeTransport();
        exact.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, new byte[KernelBridge.SSDT_STATUS_SIZE]);
        exact.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(0, 0, 0));
        assertTrue(new KernelBridge(exact).state().supported(), "整长即视为合法");
    }

    @Test
    void SSDT响应截断返回不支持() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, new byte[40]);

        KernelState state = new KernelBridge(transport).state();

        assertFalse(state.supported());
        assertNotNull(state.note());
    }

    @Test
    void 驱动枚举声明条目数超出实际字节返回不支持() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        // 头部声明 returned=5，但只带了 1 条条目的字节
        transport.responses.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, driverPage(5, 5, 1, 0x2));

        assertFalse(new KernelBridge(transport).state().supported());
    }

    // ========================================================================================
    // 设备缺失 / 异常
    // ========================================================================================

    @Test
    void 设备不可用返回不支持() {
        FakeTransport transport = new FakeTransport();
        transport.failing.add(KernelBridge.IOCTL_QUERY_SSDT_STATUS);

        KernelState state = new KernelBridge(transport).state();

        assertFalse(state.supported());
        assertNotNull(state.note());
    }

    @Test
    void 传输异常返回不支持() {
        FakeTransport transport = new FakeTransport();
        transport.thrown = new IllegalStateException("device open failed");

        KernelState state = new KernelBridge(transport).state();

        assertFalse(state.supported());
        assertTrue(state.note().contains("异常"));
    }

    @Test
    void 未配置传输通道返回不支持() {
        assertFalse(new KernelBridge(null).state().supported());
    }

    // ========================================================================================
    // 分页
    // ========================================================================================

    @Test
    void 驱动枚举分页累加未签名数() {
        FakeTransport transport = new FakeTransport();
        transport.responses.put(KernelBridge.IOCTL_QUERY_SSDT_STATUS, ssdt(true, 500, 0));
        transport.sequences.put(KernelBridge.IOCTL_QUERY_DRIVER_STATUS, new ArrayDeque<>(java.util.List.of(
                driverPage(3, 2, 2, 0x2, 0x1),
                driverPage(3, 1, 3, 0x2))));

        KernelState state = new KernelBridge(transport).state();

        assertTrue(state.supported());
        assertEquals(2, state.unsignedDriverCount(), "两页共 2 个未签名驱动");
    }

    // ========================================================================================
    // 既有 KernelState 语义
    // ========================================================================================

    @Test
    void unsupported既有语义不变() {
        KernelState state = KernelState.unsupported("原因");

        assertFalse(state.supported());
        assertEquals(0, state.ssdtHooks());
        assertEquals(0, state.idtHooks());
        assertEquals(0, state.unsignedDriverCount());
        assertEquals(0, state.suspiciousCallbackCount());
        assertEquals("原因", state.note());
    }

    // ========================================================================================
    // 夹具
    // ========================================================================================

    /** PACC_SSDT_STATUS：Supported/EntryCount/DivergenceCount，其余字段留 0。 */
    private static byte[] ssdt(boolean located, int entries, long divergence) {
        ByteBuffer b = ByteBuffer.allocate(KernelBridge.SSDT_STATUS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) (located ? 1 : 0));
        b.putInt(4, entries);
        b.putInt(8, (int) divergence);
        return b.array();
    }

    /** PACC_IDT_STATUS：Supported/EntryCount(偏移 4)/AnomalyCount(偏移 8)，其余字段留 0。 */
    private static byte[] idt(boolean located, int entries, long anomalies) {
        ByteBuffer b = ByteBuffer.allocate(KernelBridge.IDT_STATUS_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.put(0, (byte) (located ? 1 : 0));
        b.putInt(4, entries);
        b.putInt(8, (int) anomalies);
        return b.array();
    }

    /** PACC_CALLBACK_REPORT 头部：ClassSupported/TotalRegistered/Suspicious，类别体留 0。 */
    private static byte[] callbackPage(long classMask, long total, long suspicious) {
        ByteBuffer b = ByteBuffer.allocate(KernelBridge.CALLBACK_REPORT_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, (int) classMask);
        b.putInt(4, (int) total);
        b.putInt(8, (int) suspicious);
        return b.array();
    }

    /** PACC_DRIVER_REPORT：头部 + N 条 PACC_DRIVER_ENTRY（仅回填 Flags）。 */
    private static byte[] driverPage(long total, int returned, long nextIndex, int... flags) {
        ByteBuffer b = ByteBuffer.allocate(KernelBridge.DRIVER_REPORT_HEADER_SIZE
                + flags.length * KernelBridge.DRIVER_ENTRY_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(0, (int) total);
        b.putInt(4, returned);
        b.putInt(8, (int) nextIndex);
        for (int i = 0; i < flags.length; i++) {
            b.putInt(KernelBridge.DRIVER_REPORT_HEADER_SIZE + i * KernelBridge.DRIVER_ENTRY_SIZE + 1048, flags[i]);
        }
        return b.array();
    }

    private static final class FakeTransport implements KernelTransport {

        final Map<Integer, byte[]> responses = new HashMap<>();
        final Map<Integer, Deque<byte[]>> sequences = new HashMap<>();
        final Set<Integer> failing = new HashSet<>();
        RuntimeException thrown;
        byte[] lastDriverRequest = new byte[0];

        @Override
        public byte[] query(int ioctl, byte[] request) {
            if (thrown != null) {
                throw thrown;
            }
            if (failing.contains(ioctl)) {
                return null;
            }
            Deque<byte[]> sequence = sequences.get(ioctl);
            if (sequence != null && !sequence.isEmpty()) {
                return sequence.poll();
            }
            if (ioctl == KernelBridge.IOCTL_QUERY_DRIVER_STATUS) {
                lastDriverRequest = request;
            }
            return responses.get(ioctl);
        }
    }
}
