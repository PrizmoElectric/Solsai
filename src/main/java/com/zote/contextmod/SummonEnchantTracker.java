package com.zote.contextmod;

import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player, per-summon-type enchant loadout registry — e.g. "arrow" and "shield" each get
 * their own independent enchant set, unlike EnchantTracker's single global per-player set
 * (which stays untouched, still backing the player's own passive-buff phantom-stack system).
 *
 * `type` is a free-form String, not an enum, so a future summon type (e.g. "sword") needs no
 * change here — callers just pick a new type string.
 *
 * Consumers (e.g. ArrowManifestManager) read getActiveSet(uuid, type) directly and hand-check
 * whichever specific enchant IDs they know how to apply — this class only tracks which IDs are
 * toggled on, it doesn't compute or apply any gameplay effect itself.
 */
public class SummonEnchantTracker {

    // playerUUID -> summonType -> set of active enchantment IDs
    private static final Map<UUID, Map<String, Set<Identifier>>> active = new ConcurrentHashMap<>();

    /** Toggles the enchantment for this player+type; returns true if now active. */
    public static boolean toggle(UUID player, String type, Identifier id) {
        Set<Identifier> s = active
            .computeIfAbsent(player, k -> new ConcurrentHashMap<>())
            .computeIfAbsent(type, k -> ConcurrentHashMap.newKeySet());
        boolean added = !s.remove(id); // remove() returns true if it was present
        if (added) s.add(id);
        return added;
    }

    public static Set<Identifier> getActiveSet(UUID player, String type) {
        Map<String, Set<Identifier>> byType = active.get(player);
        if (byType == null) return Set.of();
        Set<Identifier> s = byType.get(type);
        return s != null ? Collections.unmodifiableSet(s) : Set.of();
    }

    public static void clear(UUID player, String type) {
        Map<String, Set<Identifier>> byType = active.get(player);
        if (byType != null) byType.remove(type);
    }

    /** Clears every type for this player — called from the same player-disconnect cleanup
     *  that already calls EnchantTracker.clearPlayer(). */
    public static void clearPlayer(UUID player) {
        active.remove(player);
    }

    public static void onServerStart() {
        active.clear();
    }
}
