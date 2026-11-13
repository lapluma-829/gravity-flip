package com.gravityflip.client;

import com.gravityflip.GravityDirection;
import com.gravityflip.PlayerGravity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端表现层：只旋转玩家模型，不旋转相机。
 *
 * 之前也做过相机滚动，但滚动后鼠标方向会跟着变（上下左右全反），手感很差，
 * 所以这里保持视角不变，只让身体按重力方向躺下/倒立。
 *
 * 旋转原则：把模型的「上」对齐到 -重力方向。
 * 重力向上时模型倒立（脚朝上、头朝下），逻辑上就是脚踩天花板。
 */
@OnlyIn(Dist.CLIENT)
public final class GravityRenderEvents {

    /** 玩家模型绕哪根轴转多少度。重力都是轴向的，所以只需要 X 或 Z 一根轴。 */
    private static final class BodyRotation {
        final boolean aboutX;
        final float degrees;

        BodyRotation(boolean aboutX, float degrees) {
            this.aboutX = aboutX;
            this.degrees = degrees;
        }
    }

    private GravityRenderEvents() {
    }

    public static void register() {
        MinecraftForge.EVENT_BUS.register(new GravityRenderEvents());
    }

    private static BodyRotation rotationOf(GravityDirection direction) {
        return switch (direction) {
            case DOWN -> null;
            case UP -> new BodyRotation(true, 180.0F);
            case NORTH -> new BodyRotation(true, 90.0F);
            case SOUTH -> new BodyRotation(true, -90.0F);
            case EAST -> new BodyRotation(false, 90.0F);
            case WEST -> new BodyRotation(false, -90.0F);
        };
    }

    @SubscribeEvent
    public void onRenderPlayerPre(RenderPlayerEvent.Pre event) {
        Player player = event.getEntity();
        BodyRotation rotation = rotationOf(PlayerGravity.get(player));
        if (rotation == null) {
            return;
        }
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        // 绕身体中部旋转，而不是绕脚底，避免模型穿进方块。
        pose.translate(0.0D, 0.9D, 0.0D);
        if (rotation.aboutX) {
            pose.mulPose(Axis.XP.rotationDegrees(rotation.degrees));
        } else {
            pose.mulPose(Axis.ZP.rotationDegrees(rotation.degrees));
        }
        pose.translate(0.0D, -0.9D, 0.0D);
    }

    @SubscribeEvent
    public void onRenderPlayerPost(RenderPlayerEvent.Post event) {
        if (rotationOf(PlayerGravity.get(event.getEntity())) != null) {
            event.getPoseStack().popPose();
        }
    }
}
