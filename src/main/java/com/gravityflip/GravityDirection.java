package com.gravityflip;

import net.minecraft.world.phys.Vec3;

/**
 * 六个可能的重力方向。id 用于网络同步与存档，改动顺序会让旧存档错位。
 */
public enum GravityDirection {
    DOWN(0, new Vec3(0.0D, -1.0D, 0.0D), "下"),
    UP(1, new Vec3(0.0D, 1.0D, 0.0D), "上"),
    NORTH(2, new Vec3(0.0D, 0.0D, -1.0D), "北"),
    SOUTH(3, new Vec3(0.0D, 0.0D, 1.0D), "南"),
    WEST(4, new Vec3(-1.0D, 0.0D, 0.0D), "西"),
    EAST(5, new Vec3(1.0D, 0.0D, 0.0D), "东");

    private static final GravityDirection[] VALUES = values();

    public final int id;
    public final Vec3 vector;
    public final String label;

    GravityDirection(int id, Vec3 vector, String label) {
        this.id = id;
        this.vector = vector;
        this.label = label;
    }

    public static GravityDirection byId(int id) {
        if (id < 0 || id >= VALUES.length) {
            return DOWN;
        }
        return VALUES[id];
    }

    public static GravityDirection[] all() {
        return VALUES;
    }
}
