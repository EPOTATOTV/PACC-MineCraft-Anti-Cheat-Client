package com.potatotv.pacc.nukkit;

import com.potatotv.pacc.bedrock.NukkitConfigLoader;
import com.potatotv.pacc.bedrock.NukkitPlatformAdapter;
import com.potatotv.pacc.bedrock.PaccNukkitBasePlugin;
import com.potatotv.pacc.server.core.model.ServerType;

/**
 * Nukkit 插件入口。检测逻辑、监听器与上报都在共享的 {@link PaccNukkitBasePlugin} 里，这里只声明平台。
 */
public final class PaccNukkitPlugin extends PaccNukkitBasePlugin {

    @Override
    protected ServerType serverType() {
        return ServerType.NUKKIT;
    }

    @Override
    protected NukkitPlatformAdapter createProtocolAdapter(NukkitConfigLoader loader) {
        return new NukkitPlatformAdapter(this, loader, ServerType.NUKKIT);
    }
}
