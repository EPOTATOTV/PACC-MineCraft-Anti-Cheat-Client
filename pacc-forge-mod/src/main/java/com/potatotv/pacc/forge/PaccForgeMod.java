package com.potatotv.pacc.forge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PACC Forge 客户端模组入口。
 *
 * <p>只做本地检测、留痕与红屏强制警告，不含任何封禁 / 踢出能力。检测数据只来自玩家设备本机。
 */
@Mod(PaccForgeMod.MOD_ID)
public final class PaccForgeMod {

    public static final String MOD_ID = "pacc";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public PaccForgeMod() {
        PaccClientState state = PaccClientState.get();
        MinecraftForge.EVENT_BUS.register(new ClientTickHandler(state));
        LOGGER.info("PACC ClientGuard (Forge) 已加载，客户端内核版本 {}", state.clientVersion());
    }
}
