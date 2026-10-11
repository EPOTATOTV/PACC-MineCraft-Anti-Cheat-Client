package com.potatotv.pacc.fabric;

import net.fabricmc.api.ClientModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PACC Fabric 客户端模组入口。
 *
 * <p>只做本地检测、留痕与红屏强制警告，不含任何封禁 / 踢出能力。检测数据只来自玩家设备本机。
 */
public final class PaccFabricMod implements ClientModInitializer {

    public static final String MOD_ID = "pacc";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        PaccClientState state = PaccClientState.get();
        FabricClientEvents.register(state);
        LOGGER.info("PACC ClientGuard (Fabric) 已加载，客户端内核版本 {}", state.clientVersion());
    }
}
