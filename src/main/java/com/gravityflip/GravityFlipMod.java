package com.gravityflip;

import com.gravityflip.net.GravityPacket;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;
import org.slf4j.Logger;

/**
 * 主入口：注册事件监听器与网络通道。
 */
@Mod(GravityFlipMod.MODID)
public class GravityFlipMod {
    public static final String MODID = "gravityflip";
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String PROTOCOL_VERSION = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    public GravityFlipMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        modBus.addListener(this::commonSetup);
        MinecraftForge.EVENT_BUS.register(new GravityHandler());
        // 客户端渲染/相机钩子只在物理客户端注册（用 DistExecutor 避免服务端加载客户端类）。
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> com.gravityflip.client.GravityRenderEvents.register());
    }

    private void commonSetup(final FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            int id = 0;
            CHANNEL.messageBuilder(GravityPacket.class, id++, NetworkDirection.PLAY_TO_CLIENT)
                    .encoder(GravityPacket::encode)
                    .decoder(GravityPacket::new)
                    .consumerMainThread(GravityPacket::handle)
                    .add();
        });
    }
}
