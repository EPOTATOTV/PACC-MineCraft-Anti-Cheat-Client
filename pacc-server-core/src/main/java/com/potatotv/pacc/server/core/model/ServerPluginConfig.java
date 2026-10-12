package com.potatotv.pacc.server.core.model;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * 服务端检测配置。不可变，通过 {@link Builder} 组装。
 *
 * <p>处置级别由 {@code punishmentMode} 控制：0=仅告警，1=踢出，2=封禁。默认 0（仅告警）。踢出与封禁都只
 * 作用于本服务器，必须由运维显式配置才生效。
 */
public final class ServerPluginConfig {

    private final double maxHorizontalSpeedPerTick;
    private final double maxVerticalClimbPerTick;
    private final double maxReachDistance;
    private final int maxCps;
    private final int maxPlacePerTick;
    private final double maxFallDistanceNoDamage;
    private final int flyAirborneTicksTrigger;
    private final int confidenceThreshold;
    private final Set<CheckType> enabledChecks;
    private final int punishmentMode;
    private final Set<String> cheatClientBrands;
    private final Set<String> cheatModNames;
    private final Set<String> cheatModHashes;
    private final Set<String> cheatAddonUuids;
    private final Set<String> cheatAddonKeywords;

    private ServerPluginConfig(Builder b) {
        this.maxHorizontalSpeedPerTick = b.maxHorizontalSpeedPerTick;
        this.maxVerticalClimbPerTick = b.maxVerticalClimbPerTick;
        this.maxReachDistance = b.maxReachDistance;
        this.maxCps = b.maxCps;
        this.maxPlacePerTick = b.maxPlacePerTick;
        this.maxFallDistanceNoDamage = b.maxFallDistanceNoDamage;
        this.flyAirborneTicksTrigger = b.flyAirborneTicksTrigger;
        this.confidenceThreshold = b.confidenceThreshold;
        this.enabledChecks = Set.copyOf(b.enabledChecks);
        this.punishmentMode = b.punishmentMode;
        this.cheatClientBrands = Set.copyOf(b.cheatClientBrands);
        this.cheatModNames = Set.copyOf(b.cheatModNames);
        this.cheatModHashes = Set.copyOf(b.cheatModHashes);
        this.cheatAddonUuids = Set.copyOf(b.cheatAddonUuids);
        this.cheatAddonKeywords = Set.copyOf(b.cheatAddonKeywords);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 默认配置：全部检查开启，处置级别为仅告警。 */
    public static ServerPluginConfig defaults() {
        return builder().build();
    }

    public double maxHorizontalSpeedPerTick() {
        return maxHorizontalSpeedPerTick;
    }

    public double maxVerticalClimbPerTick() {
        return maxVerticalClimbPerTick;
    }

    public double maxReachDistance() {
        return maxReachDistance;
    }

    public int maxCps() {
        return maxCps;
    }

    public int maxPlacePerTick() {
        return maxPlacePerTick;
    }

    public double maxFallDistanceNoDamage() {
        return maxFallDistanceNoDamage;
    }

    public int flyAirborneTicksTrigger() {
        return flyAirborneTicksTrigger;
    }

    public int confidenceThreshold() {
        return confidenceThreshold;
    }

    public Set<CheckType> enabledChecks() {
        return enabledChecks;
    }

    public int punishmentMode() {
        return punishmentMode;
    }

    public Set<String> cheatClientBrands() {
        return cheatClientBrands;
    }

    public Set<String> cheatModNames() {
        return cheatModNames;
    }

    /** 作弊 Mod 文件哈希黑名单（小写 sha256）。改名无法绕过，见 {@code pacc:mods} 协议。 */
    public Set<String> cheatModHashes() {
        return cheatModHashes;
    }

    public Set<String> cheatAddonUuids() {
        return cheatAddonUuids;
    }

    public Set<String> cheatAddonKeywords() {
        return cheatAddonKeywords;
    }

    /** 该检查项是否开启。 */
    public boolean isEnabled(CheckType type) {
        return enabledChecks.contains(type);
    }

    /** 配置允许的最高处置级别。 */
    public PunishmentType maxPunishment() {
        return switch (punishmentMode) {
            case 1 -> PunishmentType.KICK;
            case 2 -> PunishmentType.BAN;
            default -> PunishmentType.ALERT;
        };
    }

    public static final class Builder {

        private double maxHorizontalSpeedPerTick = 0.42;
        private double maxVerticalClimbPerTick = 0.42;
        private double maxReachDistance = 3.1;
        private int maxCps = 16;
        private int maxPlacePerTick = 1;
        private double maxFallDistanceNoDamage = 3.0;
        private int flyAirborneTicksTrigger = 20;
        private int confidenceThreshold = 60;
        private Set<CheckType> enabledChecks = EnumSet.allOf(CheckType.class);
        private int punishmentMode = 0;
        private Set<String> cheatClientBrands = Set.of();
        private Set<String> cheatModNames = Set.of();
        private Set<String> cheatModHashes = Set.of();
        private Set<String> cheatAddonUuids = Set.of();
        private Set<String> cheatAddonKeywords = Set.of();

        private Builder() {
        }

        public Builder maxHorizontalSpeedPerTick(double v) {
            this.maxHorizontalSpeedPerTick = v;
            return this;
        }

        public Builder maxVerticalClimbPerTick(double v) {
            this.maxVerticalClimbPerTick = v;
            return this;
        }

        public Builder maxReachDistance(double v) {
            this.maxReachDistance = v;
            return this;
        }

        public Builder maxCps(int v) {
            this.maxCps = v;
            return this;
        }

        public Builder maxPlacePerTick(int v) {
            this.maxPlacePerTick = v;
            return this;
        }

        public Builder maxFallDistanceNoDamage(double v) {
            this.maxFallDistanceNoDamage = v;
            return this;
        }

        public Builder flyAirborneTicksTrigger(int v) {
            this.flyAirborneTicksTrigger = v;
            return this;
        }

        public Builder confidenceThreshold(int v) {
            this.confidenceThreshold = v;
            return this;
        }

        public Builder enabledChecks(Set<CheckType> v) {
            this.enabledChecks = Objects.requireNonNull(v, "enabledChecks");
            return this;
        }

        public Builder punishmentMode(int v) {
            this.punishmentMode = v;
            return this;
        }

        public Builder cheatClientBrands(Set<String> v) {
            this.cheatClientBrands = Objects.requireNonNull(v, "cheatClientBrands");
            return this;
        }

        public Builder cheatModNames(Set<String> v) {
            this.cheatModNames = Objects.requireNonNull(v, "cheatModNames");
            return this;
        }

        public Builder cheatModHashes(Set<String> v) {
            this.cheatModHashes = Objects.requireNonNull(v, "cheatModHashes");
            return this;
        }

        public Builder cheatAddonUuids(Set<String> v) {
            this.cheatAddonUuids = Objects.requireNonNull(v, "cheatAddonUuids");
            return this;
        }

        public Builder cheatAddonKeywords(Set<String> v) {
            this.cheatAddonKeywords = Objects.requireNonNull(v, "cheatAddonKeywords");
            return this;
        }

        public ServerPluginConfig build() {
            return new ServerPluginConfig(this);
        }
    }
}
