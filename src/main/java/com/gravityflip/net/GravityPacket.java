package com.gravityflip.net;

import com.gravityflip.GravityDirection;
import com.gravityflip.PlayerGravity;
import java.util.UUID;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.network.NetworkEvent;

/**
 * 服务端 -> 客户端：同步某个玩家的重力方向。
 */
public class GravityPacket {
    private final UUID playerId;
    private final int directionId;

    public GravityPacket(UUID playerId, int directionId) {
        this.playerId = playerId;
        this.directionId = directionId;
    }

    public GravityPacket(FriendlyByteBuf buffer) {
        this.playerId = buffer.readUUID();
        this.directionId = buffer.readByte();
    }

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUUID(this.playerId);
        buffer.writeByte(this.directionId);
    }

    public static void handle(GravityPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            if (Minecraft.getInstance().level == null) {
                return;
            }
            Player player = Minecraft.getInstance().level.getPlayerByUUID(packet.playerId);
            if (player != null) {
                PlayerGravity.set(player, GravityDirection.byId(packet.directionId));
            }
        });
        context.setPacketHandled(true);
    }
}
