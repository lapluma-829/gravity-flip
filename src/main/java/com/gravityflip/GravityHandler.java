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
 * 鍙椾激瑙﹀彂缈昏浆 + 姣?tick 鏂藉姞鑷畾涔夐噸鍔涖€? */
public class GravityHandler {
    /** 鍘熺増姣?tick 鐨勭珫鐩撮噸鍔涗负 -0.08锛岃繖閲岀敤鍚屾牱鐨勯噺绾с€?*/
    private static final double ACCELERATION = 0.08D;
    /** 鍚屼竴 tick 鍐呯殑澶氶噸浼ゅ锛堢伀鐒?+ 杩戞垬绛夛級鍙畻涓€娆＄炕杞€?*/
    private static final int FLIP_COOLDOWN_TICKS = 8;
    /** 娴┖鍒颁笘鐣岄珮搴︿笂闄愰檮杩戞椂鑷姩鎭㈠锛岄伩鍏嶈鍗″湪澶╄姳鏉夸笂鍐嶄篃涓嬩笉鏉ャ€?*/
    private static final double WORLD_CEILING = 318.0D;

    private static final Map<UUID, Long> LAST_FLIP = new ConcurrentHashMap<>();

    // ---------------------------------------------------------------- 鍙椾激缈昏浆

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
        // 鍙戠粰鎵€鏈夎兘鐪嬪埌璇ョ帺瀹剁殑瀹㈡埛绔紙鍒汉涔熻鐪嬪埌浠栧€掔珛/妯汉锛夛紝浠ュ強浠栬嚜宸便€?        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GravityPacket(player.getUUID(), next.id));
        player.displayClientMessage(Component.literal("閲嶅姏缈昏浆锛? + next.label), true);
        GravityFlipMod.LOGGER.debug("{} 鐨勯噸鍔涚炕杞负 {}", player.getGameProfile().getName(), next.label);
    }

    // ---------------------------------------------------------------- 閲嶅姏鐗╃悊

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Player player = event.player;

        // 瀹㈡埛绔彧妯℃嫙鏈湴鐜╁锛屽叾浠栫帺瀹剁敱鏈嶅姟绔寘椹卞姩浣嶇疆銆?        if (player.level().isClientSide && !ClientGravityHooks.isLocalPlayer(player)) {
            return;
        }

        GravityDirection direction = PlayerGravity.get(player);
        if (direction == GravityDirection.DOWN) {
            return; // 鍘熺増閲嶅姏锛屾棤闇€骞查
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
            // 鎶垫秷鍘熺増杩欎竴 tick 宸茬粡鏂藉姞鐨?-0.08锛屽啀鐢变笅闈㈡寜鐩爣鏂瑰悜閲嶆柊鍔犱笂銆?            motion = motion.add(0.0D, ACCELERATION, 0.0D);
        }
        // 椤跺埌澶╂姢鏉挎椂涓嶅啀缁х画鍔犻€燂紝閬垮厤璐寸潃鏂瑰潡鎶栧姩/绌块€忋€?        if (direction.vector.y > 0.0D && player.verticalCollision && !player.verticalCollisionBelow
                && motion.y > 0.0D) {
            motion = new Vec3(motion.x, 0.0D, motion.z);
        }
        motion = motion.add(direction.vector.scale(ACCELERATION));
        player.setDeltaMovement(motion);
    }

    // ---------------------------------------------------------------- 鍚屾涓庢竻鐞?
    @SubscribeEvent
    public void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        // 鍏堟妸鑷繁鐨勬柟鍚戝箍鎾粰鍛ㄥ洿锛堝惈鑷繁锛夛紝鍐嶆妸鍏朵粬浜虹殑鏂瑰悜琛ュ彂缁欐柊鐜╁銆?        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
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
        // 姝讳骸閲嶇敓 / 鎹㈢淮搴︼細閲嶇疆涓烘甯搁噸鍔涖€?        PlayerGravity.set(player, GravityDirection.DOWN);
        GravityFlipMod.CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> player),
                new GravityPacket(player.getUUID(), GravityDirection.DOWN.id));
    }

    @SubscribeEvent
    public void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        LAST_FLIP.remove(id);
        PlayerGravity.forget(id);
    }
}
