package com.gravityflip;

import com.gravityflip.client.ClientGravityHooks;
import com.gravityflip.net.GravityPacket;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.network.PacketDistributor;

/**
 * 受伤触发翻转 + 每 tick 施加自定义重力。
 */
public class GravityHandler {
    /** 原版每 tick 的竖直重力为 -0.08，这里用同样的量级。 */
    private static final double ACCELERATION = 0.08D;
    /** 同一 tick 内的多重伤害（火焰 + 近战等）只算一次翻转。 */
    private static final int FLIP_COOLDOWN_TICKS = 8;
    /** 浮空到世界高度上限附近时自动恢复，避免被卡在天花板上再也下不来。 */
    private static final double WORLD_CEILING = 318.0D;

    private static final Map<UUID, Long> LAST_FLIP = new ConcurrentHashMap<>();
    /** 沿重力方向累计的「坠落」距离，用于自定义坠落伤害。 */
    private static final Map<UUID, Double> FALL_DISTANCE = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 受伤翻转

    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        if (event.getAmount() <= 0.0F) {
            return;
        }

        long now = player.level().getGameTime();
        Long last = LAST_FLIP.get(player.getUUID());
        if (last != null && now - last < FLIP_COOLDOWN_TICKS) {
            return;
        }
        LAST_FLIP.put(player.getUUID(), now);

        GravityDirection next = randomOther(PlayerGravity.get(player));
        flip(player, next);
    }

    private static GravityDirection randomOther(GravityDirection current) {
        GravityDirection[] all = GravityDirection.all();
        GravityDirection next;
        do {
            next = all[ThreadLocalRandom.current().nextInt(all.length)];
        } while (next == current);
        return next;
    }

    private static void flip(ServerPlayer player, GravityDirection next) {
        PlayerGravity.set(player, next);
        // 发给所有能看到该玩家的客户端（别人也要看到他倒立/横躺），以及他自己。
        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GravityPacket(player.getUUID(), next.id));
        player.displayClientMessage(Component.literal("重力翻转：" + next.label), true);
        GravityFlipMod.LOGGER.debug("{} 的重力翻转为 {}", player.getGameProfile().getName(), next.label);
    }

    // ---------------------------------------------------------------- 重力物理

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;

        // 客户端只模拟本地玩家，其他玩家由服务端包驱动位置。
        if (player.level().isClientSide && !ClientGravityHooks.isLocalPlayer(player)) {
            return;
        }

        GravityDirection direction = PlayerGravity.get(player);
        if (direction == GravityDirection.DOWN) {
            return; // 原版重力，无需干预
        }
        if (!canBeAffected(player)) {
            return;
        }

        if (direction.vector.y > 0.0D && player.getY() >= WORLD_CEILING) {
            if (!player.level().isClientSide) {
                flip((ServerPlayer) player, GravityDirection.DOWN);
            }
            return;
        }

        applyDirectionalGravity(player, direction);
        applyFallDamage(player, direction);
    }

    /**
     * 原版坠落伤害只统计向下的位移，重力不是「下」时就不会触发。
     * 这里沿重力方向自行累计距离，撞到方块时按原版公式（超过 3 格后每格 1 点）扣血。
     */
    private static void applyFallDamage(Player player, GravityDirection direction) {
        if (player.level().isClientSide) {
            return;
        }
        UUID id = player.getUUID();
        Vec3 motion = player.getDeltaMovement();
        double along = motion.dot(direction.vector); // 沿重力方向的速度（正数 = 正在"下落"）
        boolean landed;
        if (direction.vector.y > 0.0D) {
            landed = player.verticalCollision;
        } else if (direction.vector.y < 0.0D) {
            landed = player.onGround();
        } else {
            landed = player.horizontalCollision;
        }

        double distance = FALL_DISTANCE.getOrDefault(id, 0.0D);
        if (landed) {
            if (distance > 3.0D) {
                player.hurt(player.damageSources().fall(), (float) (distance - 3.0D));
            }
            FALL_DISTANCE.put(id, 0.0D);
        } else if (along > 0.0D) {
            FALL_DISTANCE.put(id, distance + along);
        }
    }

    private static boolean canBeAffected(Player player) {
        return !player.isSpectator()
                && !player.isDeadOrDying()
                && !player.getAbilities().flying
                && !player.isFallFlying()
                && !player.isPassenger()
                && !player.isInWater()
                && !player.isInLava();
    }

    private static void applyDirectionalGravity(Player player, GravityDirection direction) {
        Vec3 motion = player.getDeltaMovement();
        if (!player.onGround()) {
            // 抵消原版这一 tick 已经施加的 -0.08，再由下面按目标方向重新加上。
            motion = motion.add(0.0D, ACCELERATION, 0.0D);
        }
        // 向上时顶到方块就停住，不再持续加速，避免贴着天花板抖动。
        if (direction.vector.y > 0.0D && player.verticalCollision && motion.y > 0.0D) {
            motion = new Vec3(motion.x, 0.0D, motion.z);
        }
        motion = motion.add(direction.vector.scale(ACCELERATION));
        player.setDeltaMovement(motion);
    }

    // ---------------------------------------------------------------- 同步与清理

    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 先把自己的方向广播给周围（含自己），再把其他人的方向补发给新玩家。
        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GravityPacket(player.getUUID(), PlayerGravity.get(player).id));
        MinecraftServer server = player.getServer();
        if (server == null) {
            return;
        }
        for (ServerPlayer other : server.getPlayerList().getPlayers()) {
            if (other != player) {
                GravityFlipMod.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                        new GravityPacket(other.getUUID(), PlayerGravity.get(other).id));
            }
        }
    }

    @SubscribeEvent
    public void onClone(PlayerEvent.Clone event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 死亡重生 / 换维度：重置为正常重力。
        PlayerGravity.set(player, GravityDirection.DOWN);
        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GravityPacket(player.getUUID(), GravityDirection.DOWN.id));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_FLIP.remove(id);
        FALL_DISTANCE.remove(id);
        PlayerGravity.forget(id);
    }
}
