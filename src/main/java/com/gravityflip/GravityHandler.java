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
        if (player.isInWater() || player.isInLava()) {
            FALL_DISTANCE.put(id, 0.0D); // 液体里不受坠落伤害
            return;
        }
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
            GravityFlipMod.LOGGER.info("[gravityflip] landed dir={} fallDistance={}", direction.label, distance);
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
                && !player.isPassenger();
    }

    /** 玩家是否已经在重力方向上「落地」（向上时就是顶到天花板，侧向时是贴住墙）。 */
    private static boolean isGroundedAlong(Player player, GravityDirection direction) {
        if (direction.vector.y > 0.0D) {
            return player.verticalCollision && !player.verticalCollisionBelow;
        }
        if (direction.vector.y < 0.0D) {
            return player.onGround();
        }
        return player.horizontalCollision;
    }

    private static void applyDirectionalGravity(Player player, GravityDirection direction) {
        Vec3 motion = player.getDeltaMovement();

        if (isGroundedAlong(player, direction)) {
            // 只抹掉「正往表面里钻」的速度分量；朝反方向的速度（比如跳跃）必须保留，
            // 否则跳起来会被这一行立刻按住。
            double along = motion.dot(direction.vector);
            if (along > 0.0D) {
                motion = motion.subtract(direction.vector.scale(along));
            }
            // 近似原版地面摩擦，避免在墙上/天花板上无限滑行。
            motion = new Vec3(motion.x * 0.6D, motion.y, motion.z * 0.6D);
            player.setDeltaMovement(motion);
            return;
        }

        // 抵消原版这一 tick 已经施加的 -0.08，再按目标方向重新加上。
        if (!player.onGround()) {
            motion = motion.add(0.0D, ACCELERATION, 0.0D);
        }
        motion = motion.add(direction.vector.scale(ACCELERATION));
        player.setDeltaMovement(motion);
    }

    /**
     * 原版跳跃永远朝 +Y；重力不是向下时，要把这个冲量换成沿 -重力 方向，
     * 否则倒立时按跳跃只会往天花板里撞。
     */
    @SubscribeEvent
    public void onLivingJump(net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        GravityDirection direction = PlayerGravity.get(player);
        if (direction == GravityDirection.DOWN) {
            return;
        }
        Vec3 motion = player.getDeltaMovement();
        GravityFlipMod.LOGGER.info("[gravityflip] jump dir={} before={}", direction.label, motion);
        motion = motion.subtract(0.0D, 0.42D, 0.0D);   // 抹掉原版跳跃
        motion = motion.add(direction.vector.scale(-0.42D)); // 沿反重力方向弹起
        player.setDeltaMovement(motion);
        GravityFlipMod.LOGGER.info("[gravityflip] jump after={}", motion);
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
