package com.potatotv.paccclient.probe;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * 内核状态桥接（三层架构 §4.4 / 审查报告 F-15、F-16）：把 PaccPtv 内核设备
 * （{@code platform/kernel-windows/}）暴露的两个只读查询 IOCTL 接进用户态，解析成
 * {@link KernelState}。
 *
 * <p>驱动契约（{@code pacc_common.h} / {@code pacc_driver.c}，均 METHOD_BUFFERED）：</p>
 * <ul>
 *   <li>{@code IOCTL_PACC_QUERY_SSDT_STATUS}（{@code +10}）：无输入，输出
 *       {@code PACC_SSDT_STATUS}（{@code EntryCount} / {@code DivergenceCount} /
 *       {@code FirstDivergentIndex}）。{@code DivergenceCount} 即 SSDT 分歧条目数 =
 *       {@link KernelState#ssdtHooks()}。</li>
 *   <li>{@code IOCTL_PACC_QUERY_DRIVER_STATUS}（{@code +11}）：输入 {@code PACC_DRIVER_QUERY}
 *       （{@code StartIndex} / {@code MaxCount}，分页），输出 {@code PACC_DRIVER_REPORT}
 *       （头部 + N×{@code PACC_DRIVER_ENTRY}）。逐页累计带 {@code PACC_DRIVER_FLAG_UNSIGNED}
 *       的条目数 = {@link KernelState#unsignedDriverCount()}。</li>
 * </ul>
 *
 * <p><b>能力边界（不伪造）</b>：当前驱动<b>没有</b> IDT 查询与内核回调枚举 IOCTL，也没有内核态
 * 调试标志查询（{@code pacc_common.h} 中无对应定义）。因此 {@link KernelState#idtHooks()} 与
 * {@link KernelState#suspiciousCallbackCount()} 恒为 0，并在 {@link KernelState#note()} 里显式
 * 说明「驱动未暴露」，避免把「查不了」当成「没有」（文档 §9 注意事项 2）。详情见
 * {@code platform/kernel-windows/README.md}。</p>
 *
 * <p>设备 / 通道不可用、响应长度不符或结构非法时一律返回 {@link KernelState#unsupported(String)}，
 * 保持用户态无驱动时的既有行为。</p>
 */
public final class KernelBridge {

    // ---- 设备 / IOCTL 常量（CTL_CODE(FILE_DEVICE_UNKNOWN, function, METHOD_BUFFERED, FILE_ANY_ACCESS)）----
    private static final int FILE_DEVICE_UNKNOWN = 0x22;
    private static final int PACC_IOCTL_BASE = 0x8000;
    private static final int METHOD_BUFFERED = 0;
    private static final int FILE_ANY_ACCESS = 0;

    /** {@code IOCTL_PACC_QUERY_SSDT_STATUS} = 0x220028。 */
    static final int IOCTL_QUERY_SSDT_STATUS = ctlCode(PACC_IOCTL_BASE + 10);
    /** {@code IOCTL_PACC_QUERY_DRIVER_STATUS} = 0x22002C。 */
    static final int IOCTL_QUERY_DRIVER_STATUS = ctlCode(PACC_IOCTL_BASE + 11);

    // ---- PACC_SSDT_STATUS（x64 布局：BOOLEAN×2 + 2B pad + ULONG×4 + 4B pad + ULONG64×7 = 80B）----
    static final int SSDT_STATUS_SIZE = 80;
    private static final int SSDT_OFF_SUPPORTED = 0;
    private static final int SSDT_OFF_ENTRY_COUNT = 4;
    private static final int SSDT_OFF_DIVERGENCE_COUNT = 8;

    // ---- PACC_DRIVER_REPORT（头部 16B；PACC_DRIVER_ENTRY 定长 1056B，Flags 在条目内偏移 1048）----
    static final int DRIVER_REPORT_HEADER_SIZE = 16;
    static final int DRIVER_ENTRY_SIZE = 1056;
    private static final int DRIVER_ENTRY_FLAGS_OFFSET = 1048;
    private static final int DRIVER_OFF_TOTAL_LOADED = 0;
    private static final int DRIVER_OFF_RETURNED = 4;
    private static final int DRIVER_OFF_NEXT_INDEX = 8;
    /** 单次请求条数上限，对应 {@code PACC_DRIVER_MAX_PER_CALL}。 */
    static final int DRIVER_MAX_PER_CALL = 64;
    /** 分页页数上限（16×64=1024 个模块），防止失控循环。 */
    private static final int DRIVER_MAX_PAGES = 16;
    /** 驱动签名的未签名标志，对应 {@code PACC_DRIVER_FLAG_UNSIGNED}。 */
    static final int DRIVER_FLAG_UNSIGNED = 0x00000002;

    private static final byte[] NO_INPUT = new byte[0];

    private final KernelTransport transport;

    public KernelBridge(KernelTransport transport) {
        this.transport = transport;
    }

    /** 查询内核状态；通道不可用或响应非法时返回 {@link KernelState#unsupported(String)}。 */
    public KernelState state() {
        if (transport == null) {
            return KernelState.unsupported("未配置内核传输通道");
        }

        byte[] ssdt;
        try {
            ssdt = transport.query(IOCTL_QUERY_SSDT_STATUS, NO_INPUT);
        } catch (RuntimeException e) {
            return KernelState.unsupported("内核设备查询异常：" + e.getClass().getSimpleName());
        }
        if (ssdt == null || ssdt.length < SSDT_STATUS_SIZE) {
            return KernelState.unsupported("PaccPtv 设备不可用或 SSDT 响应非法");
        }

        boolean ssdtLocated = ssdt[SSDT_OFF_SUPPORTED] != 0;
        int entryCount = saturate(u32(ssdt, SSDT_OFF_ENTRY_COUNT));
        int divergence = saturate(u32(ssdt, SSDT_OFF_DIVERGENCE_COUNT));

        int unsignedDrivers;
        try {
            unsignedDrivers = countUnsignedDrivers();
        } catch (RuntimeException e) {
            return KernelState.unsupported("内核驱动枚举异常：" + e.getClass().getSimpleName());
        }
        if (unsignedDrivers < 0) {
            return KernelState.unsupported("PaccPtv 驱动枚举响应非法");
        }

        String note = buildNote(ssdtLocated, entryCount, divergence, unsignedDrivers);
        return new KernelState(true, divergence, 0, unsignedDrivers, 0, note);
    }

    /**
     * 分页枚举已加载内核模块，统计带未签名标志的条目数。
     *
     * @return 未签名驱动数；任一页响应非法（长度不足或声明的条目数超出实际字节）时返回 -1
     */
    private int countUnsignedDrivers() {
        int start = 0;
        int unsigned = 0;
        for (int page = 0; page < DRIVER_MAX_PAGES; page++) {
            byte[] resp = transport.query(IOCTL_QUERY_DRIVER_STATUS, driverQuery(start, DRIVER_MAX_PER_CALL));
            if (resp == null || resp.length < DRIVER_REPORT_HEADER_SIZE) {
                return -1;
            }
            long totalLoaded = u32(resp, DRIVER_OFF_TOTAL_LOADED);
            long returned = u32(resp, DRIVER_OFF_RETURNED);
            long nextIndex = u32(resp, DRIVER_OFF_NEXT_INDEX);
            if (DRIVER_REPORT_HEADER_SIZE + returned * DRIVER_ENTRY_SIZE > resp.length) {
                return -1;   // 声明条目数与实际字节不符：结构损坏，不采信
            }
            for (int i = 0; i < (int) returned; i++) {
                int flags = (int) u32(resp, DRIVER_REPORT_HEADER_SIZE + i * DRIVER_ENTRY_SIZE + DRIVER_ENTRY_FLAGS_OFFSET);
                if ((flags & DRIVER_FLAG_UNSIGNED) != 0) {
                    unsigned++;
                }
            }
            if (returned == 0 || nextIndex <= start || nextIndex >= totalLoaded) {
                break;
            }
            start = (int) nextIndex;
        }
        return unsigned;
    }

    private static String buildNote(boolean ssdtLocated, int entryCount, int divergence, int unsignedDrivers) {
        StringBuilder note = new StringBuilder(128);
        note.append("内核驱动已接入：");
        if (ssdtLocated) {
            note.append("SSDT 分歧 ").append(divergence).append('/').append(entryCount).append(" 条目");
        } else {
            note.append("SSDT 快照不可用（驱动未定位到服务表）");
        }
        note.append("；未签名驱动 ").append(unsignedDrivers);
        note.append("；IDT 与内核回调枚举当前驱动未暴露 IOCTL，恒 0");
        return note.toString();
    }

    private static int ctlCode(int function) {
        return (FILE_DEVICE_UNKNOWN << 16)
                | (FILE_ANY_ACCESS << 14)
                | (function << 2)
                | METHOD_BUFFERED;
    }

    private static byte[] driverQuery(int startIndex, int maxCount) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(startIndex)
                .putInt(maxCount)
                .array();
    }

    private static long u32(byte[] buf, int offset) {
        return ByteBuffer.wrap(buf).order(ByteOrder.LITTLE_ENDIAN).getInt(offset) & 0xFFFFFFFFL;
    }

    /** ULONG 超出 int 域时饱和到 {@link Integer#MAX_VALUE}，避免溢出成负数。 */
    private static int saturate(long value) {
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
