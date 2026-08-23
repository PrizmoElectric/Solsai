package com.zote.contextmod;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import virtuoel.pehkui.api.ScaleTypes;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Player body scale control via Pehkui 3.8.3.
 *
 * SIZE  → ScaleTypes.BASE  — controls overall size (visual + hitbox + motion all derive from it).
 * REACH → ScaleTypes.REACH — controls both block-reach and entity-reach uniformly.
 *
 * Pehkui syncs scale changes to the client automatically on the next tick.
 * All writes happen on the server thread via CompletableFuture to be safe with entity state.
 */
public class PlayerBodyManager {

    public static String setSize(MinecraftServer server, String playerName, float scale) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"error\":\"player not found\"}";
        float s = Math.max(0.05f, Math.min(10.0f, scale));
        return dispatch(server, () -> ScaleTypes.BASE.getScaleData(player).setBaseScale(s));
    }

    public static String setReach(MinecraftServer server, String playerName, float mult) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"error\":\"player not found\"}";
        float m = Math.max(0.1f, Math.min(32.0f, mult));
        return dispatch(server, () -> ScaleTypes.REACH.getScaleData(player).setBaseScale(m));
    }

    public static String reset(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"error\":\"player not found\"}";
        // Default base scale is 1.0 for both types (Pehkui's own default)
        return dispatch(server, () -> {
            ScaleTypes.BASE.getScaleData(player).setBaseScale(1.0f);
            ScaleTypes.REACH.getScaleData(player).setBaseScale(1.0f);
        });
    }

    private static String dispatch(MinecraftServer server, Runnable action) {
        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                action.run();
                future.complete("{\"ok\":true}");
            } catch (Throwable e) {
                future.complete("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }
}
