package com.potatotv.pacc.nukkitmot;

import com.potatotv.pacc.bedrock.NukkitConfigLoader;
import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.bedrock.PaccNukkitBasePlugin;
import com.potatotv.pacc.server.core.model.ServerType;

/**
 * Nukkit-MOT 插件入口。与 Nukkit 版共用同一份检测实现，仅平台类型不同。
 */
public final class PaccNukkitMotPlugin extends PaccNukkitBasePlugin {

    @Override
    protected ServerType serverType() {
        return ServerType.NUKKIT_MOT;
    }

    @Override
    protected NukkitPlatformAdapter createProtocolAdapter(NukkitConfigLoader loader) {
        return new NukkitPlatformAdapter(this, loader, ServerType.NUKKIT_MOT);
    }
}
