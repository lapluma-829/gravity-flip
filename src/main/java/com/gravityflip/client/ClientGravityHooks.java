package com.gravityflip.client;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 客户端专用入口。
 *
 * 单独成类的原因：专用服务器上没有 net.minecraft.client.Minecraft，
 * 如果公共类里直接引用它，服务器加载该类时可能抛 NoClassDefFoundError。
 * 公共代码只在 isClientSide 分支里调用这里，服务器永远不会加载本类。
 */
@OnlyIn(Dist.CLIENT)
public final class ClientGravityHooks {
    private ClientGravityHooks() {
    }

    public static boolean isLocalPlayer(Player player) {
        return player == Minecraft.getInstance().player;
    }
}
