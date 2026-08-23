package com.zote.contextmod;

import com.mojang.authlib.GameProfile;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

/**
 * A fake ServerPlayerEntity used for Clone Manifestation.
 * Has the summoner's skin (via copied GameProfile properties), no gravity,
 * and is managed entirely by CloneManager tick logic.
 *
 * isDisconnected() returns false so the server never auto-kicks it.
 * writeCustomDataToNbt is a no-op to prevent a save file being written to disk.
 */
public class FakePlayerEntity extends ServerPlayerEntity {

    public FakePlayerEntity(MinecraftServer server, ServerWorld world, GameProfile profile) {
        super(server, world, profile);
    }

    @Override
    public boolean isDisconnected() { return false; }

    @Override
    public boolean isSpectator() { return false; }

    @Override
    public boolean isCreative() { return false; }

    /** Prevent player save data being written to disk. */
    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {}
}
