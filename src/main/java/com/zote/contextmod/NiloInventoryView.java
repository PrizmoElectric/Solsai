package com.zote.contextmod;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;

/**
 * Flat 45-slot ("generic container", 9 cols x 5 rows) view onto a bot's REAL PlayerInventory,
 * so it can be shown through vanilla's own GenericContainerScreenHandler /
 * ScreenHandlerType.GENERIC_9X5 — the same type chests use, already present in every client's
 * registry. Deliberately NOT a brand-new custom ScreenHandlerType: Registries.SCREEN_HANDLER is
 * one of the registries Fabric's registry-sync marks SYNCED ("Synced in OpenScreenS2CPacket",
 * per fabric-registry-sync-v0's FabricRegistryInit), so a custom type would kick every
 * connecting client that doesn't have a matching client-side registration — the same class of
 * incident as this mod's shield_block/Registries.BLOCK mistake (see the DISABLED comment on
 * that near the top of ContextMod.java). Reusing a vanilla type sidesteps that entirely.
 *
 * Index map (45 slots):
 *   0-8    hotbar   (PlayerInventory 0-8, unchanged)
 *   9-35   main     (PlayerInventory 9-35, unchanged)
 *   36-39  armor    (PlayerInventory 39,38,37,36 = helmet,chestplate,leggings,boots)
 *   40     offhand  (PlayerInventory 40)
 *   41-44  unused filler — always empty, rejects inserts (41 real slots don't fill a 6th row)
 *
 * Trade-off accepted for simplicity/safety: armor slots here don't restrict item type the way
 * vanilla's own dedicated PlayerScreenHandler armor slots do (anything can go in slot 36-39) —
 * a fully custom ScreenHandler could add that, but only by either reintroducing the registry-
 * sync risk above or a lot more code for a cosmetic nicety the original bug report didn't ask
 * for (item position accuracy did).
 */
public class NiloInventoryView implements Inventory {

    private static final int[] ARMOR_SOURCE = { 39, 38, 37, 36 }; // view index 36..39 -> real PlayerInventory index

    private final PlayerInventory real;

    public NiloInventoryView(PlayerInventory real) {
        this.real = real;
    }

    private int toReal(int viewIndex) {
        if (viewIndex < 36) return viewIndex;                  // hotbar + main, 1:1
        if (viewIndex < 40) return ARMOR_SOURCE[viewIndex - 36];
        if (viewIndex == 40) return 40;                        // offhand
        return -1;                                             // 41-44, filler
    }

    @Override public int size() { return 45; }

    @Override public boolean isEmpty() {
        for (int i = 0; i < 45; i++) if (!getStack(i).isEmpty()) return false;
        return true;
    }

    @Override public ItemStack getStack(int slot) {
        int r = toReal(slot);
        return r < 0 ? ItemStack.EMPTY : real.getStack(r);
    }

    @Override public ItemStack removeStack(int slot, int amount) {
        int r = toReal(slot);
        return r < 0 ? ItemStack.EMPTY : real.removeStack(r, amount);
    }

    @Override public ItemStack removeStack(int slot) {
        int r = toReal(slot);
        return r < 0 ? ItemStack.EMPTY : real.removeStack(r);
    }

    @Override public void setStack(int slot, ItemStack stack) {
        int r = toReal(slot);
        if (r >= 0) real.setStack(r, stack);
    }

    @Override public boolean isValid(int slot, ItemStack stack) {
        return toReal(slot) >= 0; // filler slots (41-44) reject any insert
    }

    @Override public void markDirty() { real.markDirty(); }

    @Override public boolean canPlayerUse(PlayerEntity player) { return true; }

    @Override public void clear() {
        for (int i = 0; i < 41; i++) setStack(i, ItemStack.EMPTY);
    }
}
