package com.potatotv.paccclient.probe;

/**
 * {@link KernelTransport} 的生产实现：把 IOCTL 转发给 PaccManager 原生探针。
 *
 * <p>仓库既有约定是「用户态原生能力一律由桌面壳 PaccManager 经 {@code 127.0.0.1} 回环提供」
 * （见 {@link PaccProbeClient}，ptv-client 不引入 JNI / JNA / FFM），因此本实现不做原生调用，
 * 而是把 {@code (ioctl, request)} 原样交给 PaccManager 的 {@code /probe/kernel}，由其打开
 * {@code \\.\PaccPtv} 设备并调用 {@code DeviceIoControl}，再回传输出字节。</p>
 *
 * <p><b>待实现的原生桩（Windows 侧）</b>：{@code tools/windows-gui/Services/SystemProbeEndpoint.cs}
 * 目前没有 {@code /probe/kernel} 路由，需补一个只读转发路由（CreateFileW {@code \\.\PaccPtv} +
 * DeviceIoControl，仅放行 {@code pacc_common.h} 里列出的查询码，输出缓冲由路由按请求分配）。
 * 在该桩落地前本通道恒返回 {@code null}，{@link KernelBridge} 据此返回
 * {@link KernelState#unsupported(String)}，绝不伪造内核数据。</p>
 */
public final class PaccKernelTransport implements KernelTransport {

    private static final byte[] NO_INPUT = new byte[0];

    private final PaccProbeClient client;

    public PaccKernelTransport() {
        this(new PaccProbeClient());
    }

    public PaccKernelTransport(PaccProbeClient client) {
        this.client = client == null ? new PaccProbeClient() : client;
    }

    @Override
    public byte[] query(int ioctl, byte[] request) {
        return client.kernelQuery(ioctl, request == null ? NO_INPUT : request);
    }
}
