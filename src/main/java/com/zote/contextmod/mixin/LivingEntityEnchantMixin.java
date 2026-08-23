package com.zote.contextmod.mixin;

import com.zote.contextmod.EnchantTracker;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;

/**
 * Appends phantom ItemStacks to the armor and hand item iterables of
 * ServerPlayerEntities, so that EnchantmentHelper picks up active passive
 * enchantments for ALL damage/loot/effect calculations.
 *
 * Only fires on the server (instanceof ServerPlayerEntity guard).
 * The client never sees these stacks, so the player's visual equipment is unchanged.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityEnchantMixin {

    @Inject(method = "getArmorItems", at = @At("RETURN"), cancellable = true)
    private void prizmo_phantomArmor(CallbackInfoReturnable<Iterable<ItemStack>> cir) {
        if (!((Object) this instanceof ServerPlayerEntity player)) return;
        List<ItemStack> phantom = EnchantTracker.getPhantomArmorItems(player.getUuid());
        if (phantom.isEmpty()) return;
        List<ItemStack> out = new ArrayList<>();
        cir.getReturnValue().forEach(out::add);
        out.addAll(phantom);
        cir.setReturnValue(out);
    }

    @Inject(method = "getHandItems", at = @At("RETURN"), cancellable = true)
    private void prizmo_phantomHands(CallbackInfoReturnable<Iterable<ItemStack>> cir) {
        if (!((Object) this instanceof ServerPlayerEntity player)) return;
        List<ItemStack> phantom = EnchantTracker.getPhantomHandItems(player.getUuid());
        if (phantom.isEmpty()) return;
        List<ItemStack> out = new ArrayList<>();
        cir.getReturnValue().forEach(out::add);
        out.addAll(phantom);
        cir.setReturnValue(out);
    }
}
