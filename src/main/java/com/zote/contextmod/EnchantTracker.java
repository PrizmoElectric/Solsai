package com.zote.contextmod;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-player "passive enchantment" registry.
 *
 * Active enchantments are stored as Identifier sets per player UUID.
 * Phantom ItemStacks are built lazily and cached; the cache is invalidated whenever the set changes.
 *
 * The mixin LivingEntityEnchantMixin appends these phantom stacks to
 * ServerPlayerEntity.getArmorItems() and getHandItems() so that the vanilla
 * EnchantmentHelper picks them up for ALL enchantment effect calculations
 * (protection, damage, looting, mending, etc.) without touching the player's real equipment.
 *
 * Phantom stacks use vanilla probe items (diamond armor + weapon + tool + bow) so that
 * Enchantment.isAcceptableItem() correctly routes each enchantment to the right slot type.
 * Modded enchantments that accept vanilla items will work automatically.
 */
public class EnchantTracker {

    // playerUUID → set of active enchantment IDs
    private static final Map<UUID, Set<Identifier>> active = new ConcurrentHashMap<>();

    // Cached phantom stacks, rebuilt on demand after each set change
    private static final Map<UUID, List<ItemStack>> armorCache = new ConcurrentHashMap<>();
    private static final Map<UUID, List<ItemStack>> handCache  = new ConcurrentHashMap<>();

    // Probe order matters: more specific slots first.
    // Feather Falling → boots (checked before chestplate).
    // Sharpness → sword (checked before pickaxe).
    private static final List<Item> ARMOR_PROBES = List.of(
        Items.DIAMOND_BOOTS,
        Items.DIAMOND_LEGGINGS,
        Items.DIAMOND_CHESTPLATE,
        Items.DIAMOND_HELMET
    );
    private static final List<Item> HAND_PROBES = List.of(
        Items.DIAMOND_SWORD,
        Items.DIAMOND_PICKAXE,
        Items.BOW,
        Items.CROSSBOW,
        Items.TRIDENT,
        Items.FISHING_ROD,
        Items.DIAMOND_AXE
    );

    // ── Public API ────────────────────────────────────────────────────────────

    public static void enable(UUID player, Identifier id) {
        active.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet()).add(id);
        invalidate(player);
    }

    public static void disable(UUID player, Identifier id) {
        Set<Identifier> s = active.get(player);
        if (s != null) { s.remove(id); invalidate(player); }
    }

    /** Toggles the enchantment; returns true if now active. */
    public static boolean toggle(UUID player, Identifier id) {
        Set<Identifier> s = active.computeIfAbsent(player, k -> ConcurrentHashMap.newKeySet());
        boolean added = !s.remove(id);  // remove returns true if it was present
        if (added) s.add(id);
        invalidate(player);
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

    public static void onServerStart() {
        active.clear();
        armorCache.clear();
        handCache.clear();
    }

    public static void clearPlayer(UUID player) {
        active.remove(player);
        invalidate(player);
    }

    // ── Phantom stack access (called from mixin on every enchantment query) ──

    public static List<ItemStack> getPhantomArmorItems(UUID uuid) {
        return armorCache.computeIfAbsent(uuid, k -> buildPhantoms(k, ARMOR_PROBES));
    }

    public static List<ItemStack> getPhantomHandItems(UUID uuid) {
        return handCache.computeIfAbsent(uuid, k -> buildPhantoms(k, HAND_PROBES));
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static List<ItemStack> buildPhantoms(UUID uuid, List<Item> probes) {
        Set<Identifier> ids = active.get(uuid);
        if (ids == null || ids.isEmpty()) return List.of();

        // One ItemStack per probe item — accumulate matching enchantments into each
        Map<Item, ItemStack> stacks = new LinkedHashMap<>();

        for (Identifier id : ids) {
            Enchantment ench = Registries.ENCHANTMENT.get(id);
            if (ench == null) continue;
            for (Item probe : probes) {
                try {
                    ItemStack test = new ItemStack(probe);
                    if (!ench.isAcceptableItem(test)) continue;
                    ItemStack phantom = stacks.computeIfAbsent(probe, ItemStack::new);
                    phantom.addEnchantment(ench, ench.getMaxLevel());
                    break;  // assign enchantment to only the first matching probe
                } catch (Exception ignored) {
                    // Guard against mods that throw in isAcceptableItem
                }
            }
        }

        return stacks.values().stream().toList();
    }

    private static void invalidate(UUID player) {
        armorCache.remove(player);
        handCache.remove(player);
    }
}
