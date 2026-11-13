package com.gravityflip;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.player.Player;

/**
 * 玩家当前重力方向的存取。
 * 服务端把方向写在玩家的 persistentData 里（随存档保存），客户端只存在内存表中，由网络包同步。
 */
public final class PlayerGravity {
    private static final String NBT_KEY = "gravityflip.direction";
    private static final Map<UUID, GravityDirection> CLIENT_STATE = new ConcurrentHashMap<>();

    private PlayerGravity() {
    }

    public static GravityDirection get(Player player) {
        if (player.level().isClientSide) {
            return CLIENT_STATE.getOrDefault(player.getUUID(), GravityDirection.DOWN);
        }
        CompoundTag data = player.getPersistentData();
        if (!data.contains(NBT_KEY)) {
            return GravityDirection.DOWN;
        }
        return GravityDirection.byId(data.getInt(NBT_KEY));
    }

    public static void set(Player player, GravityDirection direction) {
        if (player.level().isClientSide) {
            CLIENT_STATE.put(player.getUUID(), direction);
        } else {
            player.getPersistentData().putInt(NBT_KEY, direction.id);
        }
    }

    public static void forget(UUID playerId) {
        CLIENT_STATE.remove(playerId);
    }
}
