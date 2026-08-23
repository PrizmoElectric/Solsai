package com.zote.contextmod;

import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectCategory;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player passive status effect registry.
 *
 * Active effects are refreshed every second (20 ticks).
 * Disabled effects are removed from the player within one tick.
 * Uses 6000-tick (5 min) duration with 200-tick refresh threshold so effects never visibly expire.
 *
 * BENEFICIAL effects apply at amplifier 1 (Level II); all others at amplifier 0 (Level I).
 * Works with any registered StatusEffect including modded ones.
 */
public class EffectTracker {

    private static final int EFFECT_DURATION     = 6000; // 5 minutes
    private static final int REFRESH_THRESHOLD   = 200;  // re-apply when <10s left
    private static int tickCount = 0;

    // playerUUID → set of active effect IDs
    private static final Map<UUID, Set<Identifier>> active  = new ConcurrentHashMap<>();
    // tracks exactly which effects we applied (so we only remove those, not player's potions)
    private static final Map<UUID, Set<Identifier>> applied = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    /** Toggles the effect; returns true if now active. */
    public static boolean toggle(UUID player, Identifier id) {
        Set<Identifier> s = active.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        boolean added = !s.remove(id);
        if (added) s.add(id);
        return added;
    }

    public static boolean isActive(UUID player, Identifier id) {
        Set<Identifier> s = active.get(player);
        return s != null && s.contains(id);
    }

    public static Set<Identifier> getActiveSet(UUID player) {
        Set<Identifier> s = active.get(player);
        return s != null ? Collections.unmodifiableSet(s) : Set.of();
    }

    public static void clearPlayer(UUID player) {
        active.remove(player);
        applied.remove(player);  // Bug 6: also purge applied so it doesn't leak after disconnect
    }

    public static void onServerStart() {
        active.clear();
        applied.clear();
        tickCount = 0;
    }

    // ── Tick (called from ServerTickEvents on server thread) ──────────────────

    public static void tick(MinecraftServer server) {
        boolean doRefresh = (++tickCount % 20 == 0);

        // ── Reconcile: remove effects that were disabled ───────────────────────
        // Runs every tick so disabling an effect removes it immediately.
        for (Map.Entry<UUID, Set<Identifier>> e : applied.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            Set<Identifier> shouldBeActive = active.getOrDefault(e.getKey(), Set.of());
            List<Identifier> toRemove = null;
            for (Identifier id : e.getValue()) {
                if (!shouldBeActive.contains(id)) {
                    if (toRemove == null) toRemove = new ArrayList<>();
                    toRemove.add(id);
                }
            }
            if (toRemove == null) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(e.getKey());
            for (Identifier id : toRemove) {
                StatusEffect eff = Registries.STATUS_EFFECT.get(id);
                if (eff != null && player != null) player.removeStatusEffect(eff);
                e.getValue().remove(id);
            }
        }

        if (!doRefresh) return;

        // ── Apply / refresh active effects (once per second) ──────────────────
        for (Map.Entry<UUID, Set<Identifier>> e : active.entrySet()) {
            if (e.getValue().isEmpty()) continue;
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(e.getKey());
            if (player == null) continue;
            Set<Identifier> playerApplied = applied.computeIfAbsent(
                e.getKey(), k -> ConcurrentHashMap.newKeySet());
            for (Identifier id : e.getValue()) {
                StatusEffect eff = Registries.STATUS_EFFECT.get(id);
                if (eff == null) continue;
                StatusEffectInstance current = player.getStatusEffect(eff);
                if (current != null && current.getDuration() > REFRESH_THRESHOLD) continue;
                int amp = eff.getCategory() == StatusEffectCategory.BENEFICIAL ? 1 : 0;
                player.addStatusEffect(
                    new StatusEffectInstance(eff, EFFECT_DURATION, amp, false, false, true));
                playerApplied.add(id);
            }
        }
    }
}
