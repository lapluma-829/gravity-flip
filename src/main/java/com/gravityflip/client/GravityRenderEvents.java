package com.gravityflip.client;

import com.gravityflip.GravityDirection;
import com.gravityflip.PlayerGravity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/**
 * 客户端表现层：让玩家身体和相机跟着重力方向旋转。
 *
 * 旋转原则：把模型的「上」对齐到 -重力方向。
 * 重力向上时模型倒立（脚朝上、头朝下），逻辑上就是脚踩天花板。
 */
@OnlyIn(Dist.CLIENT)
public final class GravityRenderEvents {

    /** 玩家模型绕哪根轴转多少度。轴只能是 X 或 Z（重力都是轴向的）。 */
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

    // ------------------------------------------------------------ 玩家模型

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

    // ------------------------------------------------------------ 相机

    @SubscribeEvent
    public void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = minecraft.player;
        if (player == null) {
            return;
        }
        GravityDirection direction = PlayerGravity.get(player);
        if (direction == GravityDirection.DOWN) {
            return;
        }
        float roll = rollFor(direction, event.getYaw(), event.getPitch());
        event.setRoll(roll);
    }

    /**
     * 计算相机需要滚动的角度：把屏幕的「上」对齐到 -重力方向。
     * 做法是把目标的「上」向量投影到相机视平面上，再和当前的上向量求夹角。
     */
    private static float rollFor(GravityDirection direction, float yawDeg, float pitchDeg) {
        double yaw = Math.toRadians(yawDeg);
        double pitch = Math.toRadians(pitchDeg);

        // 相机朝向（与 MC 一致：yaw 绕 Y，pitch 抬头为正）
        Vec3 forward = new Vec3(
                -Math.sin(yaw) * Math.cos(pitch),
                -Math.sin(pitch),
                Math.cos(yaw) * Math.cos(pitch));
        Vec3 worldUp = new Vec3(0.0D, 1.0D, 0.0D);

        // 当前相机上方向 = forward × right，这里用 worldUp 在视平面上的投影代替
        Vec3 targetUp = direction.vector.scale(-1.0D);

        Vec3 projectedTarget = targetUp.subtract(forward.scale(targetUp.dot(forward)));
        Vec3 projectedCurrent = worldUp.subtract(forward.scale(worldUp.dot(forward)));
        if (projectedTarget.lengthSqr() < 1.0E-6D || projectedCurrent.lengthSqr() < 1.0E-6D) {
            return 0.0F;
        }
        projectedTarget = projectedTarget.normalize();
        projectedCurrent = projectedCurrent.normalize();

        double angle = Math.toDegrees(Math.atan2(
                forward.dot(projectedCurrent.cross(projectedTarget)),
                projectedCurrent.dot(projectedTarget)));
        return (float) angle;
    }
}
