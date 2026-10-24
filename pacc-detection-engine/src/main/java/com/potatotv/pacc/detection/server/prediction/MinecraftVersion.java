package com.potatotv.pacc.detection.server.prediction;

/**
 * 支持的 Minecraft Java 版本矩阵（P-01 / R-08）。
 *
 * <p>26.x 起 Mojang 改用年份版本号（26.1 / 26.2 / 26.3 / 26.4）。预测引擎不按版本复制整套
 * 物理常量，只声明版本能力差异：26.2 引入硫磺方块、硫磺洞穴与新的反弹/摩擦属性；26.4
 * 快照把 Vulkan 设为默认图形 API。世界副本与预测器通过 {@link BlockPhysics} 查表，
 * 不把方块名散落在物理公式里。</p>
 *
 * <p>Vulkan 默认化只影响渲染层。PACC 玩家端探针采集的是进程、内存、输入与系统环境数据，
 * 不依赖 OpenGL 上下文，因此 26.4 不需要改探针采集逻辑；这里仅声明能力位，供后续渲染级
 * 检测按版本分流。26.4 尚为快照，{@link #latest()} 仍返回稳定版 26.3。</p>
 */
public enum MinecraftVersion {

    /** 1.20.x：世界高度 -64..319，无硫磺方块。 */
    V1_20(1, 20, false, false),

    /** 1.21.x：世界高度 -64..319，无硫磺方块。 */
    V1_21(1, 21, false, false),

    /** 26.1：年份版本号首个正式版。 */
    V26_1(26, 1, false, false),

    /** 26.2：硫磺方块 / 硫磺洞穴 / 资源包 86.2。 */
    V26_2(26, 2, true, false),

    /** 26.3：稳定版，硫磺方块能力延续。 */
    V26_3(26, 3, true, false),

    /** 26.4：快照，Vulkan 成为默认图形 API。 */
    V26_4(26, 4, true, true);

    /** 主世界最低 Y（1.18+ 统一口径）。 */
    public static final int MIN_Y = -64;

    /** 主世界最高 Y（含）。 */
    public static final int MAX_Y = 319;

    private final int major;
    private final int minor;
    private final boolean sulfurBlocks;
    private final boolean vulkanDefault;

    MinecraftVersion(int major, int minor, boolean sulfurBlocks, boolean vulkanDefault) {
        this.major = major;
        this.minor = minor;
        this.sulfurBlocks = sulfurBlocks;
        this.vulkanDefault = vulkanDefault;
    }

    public int major() {
        return major;
    }

    public int minor() {
        return minor;
    }

    /** 该版本是否包含硫磺方块与硫磺洞穴。 */
    public boolean hasSulfurBlocks() {
        return sulfurBlocks;
    }

    /** 该版本是否默认使用 Vulkan 渲染（26.4 快照起）。 */
    public boolean hasVulkanDefault() {
        return vulkanDefault;
    }

    /** 展示名，如 {@code "26.2"}。 */
    public String displayName() {
        return major + "." + minor;
    }

    /** 当前代码声明支持的最新稳定版本；26.4 仍是快照，不计入。 */
    public static MinecraftVersion latest() {
        return V26_3;
    }

    /**
     * 从平台版本字符串解析（如 {@code "26.2"}、{@code "1.21.1"}、{@code "git-Paper-123 (MC: 26.2)"}）。
     * 无法识别时返回 {@link #latest()}——宁可多开能力，也不因版本识别失败把检测降级掉。
     */
    public static MinecraftVersion parse(String versionText) {
        if (versionText == null || versionText.isBlank()) {
            return latest();
        }
        String text = versionText;
        int marker = text.indexOf("MC:");
        if (marker >= 0) {
            text = text.substring(marker + 3);
        }
        String[] parts = text.trim().split("[^0-9]+");
        Integer major = null;
        Integer minor = null;
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            int value;
            try {
                value = Integer.parseInt(part);
            } catch (NumberFormatException ex) {
                continue;
            }
            if (major == null) {
                major = value;
            } else {
                minor = value;
                break;
            }
        }
        if (major == null) {
            return latest();
        }
        if (major == 26) {
            if (minor == null) {
                return V26_1;
            }
            if (minor >= 4) {
                return V26_4;
            }
            if (minor == 3) {
                return V26_3;
            }
            return minor >= 2 ? V26_2 : V26_1;
        }
        if (major == 1 && minor != null && minor >= 21) {
            return V1_21;
        }
        return V1_20;
    }
}
