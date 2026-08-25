package com.zote.contextmod.mixin;

import com.zote.contextmod.EnchantTracker;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Alternate route for passive-enchant application, alongside (not replacing)
 * LivingEntityEnchantMixin's phantom-stack injection into getArmorItems()/
 * getHandItems(). That mixin's two injects are both require=0 (non-fatal) because
 * on this modpack they fail to resolve — the "accessories" mod's own LivingEntity
 * mixin (accessories-fabric.mixins.json:LivingEntityMixin, confirmed via the
 * "Shift.BY=2 ... adjustLooting" warning in the server log) also rewrites those
 * same two methods, and Mixin's target-scan comes up with 0 matches for ours
 * afterward. Rather than fight that conflict, this hooks EnchantmentHelper.
 * getEquipmentLevel(Enchantment, LivingEntity) instead — a static utility method
 * no combat/accessory mod has reason to touch, and the single choke point vanilla
 * itself uses for looting, knockback, fire aspect, respiration, depth strider,
 * efficiency, aqua affinity, frost walker, soul speed, and swift sneak.
 *
 * Does NOT cover Protection or melee Sharpness — those go through
 * getProtectionAmount(this.getArmorItems(), ...) / attack-damage code that reads
 * getArmorItems()/getHandItems() directly, the same methods the other mixin can't
 * reach on this modpack. Left as a known gap; would need a call-site-specific
 * @Redirect in LivingEntity rather than another blanket method override.
 *
 * require=0 despite this being the primary mechanism, not a bonus: the project
 * default (solsai.mixins.json's defaultRequire=1) means an unresolved target
 * fails the WHOLE mod's load, which on a live production server would mean the
 * server fails to come back up on its next restart. Given this can only be
 * verified by actually restarting (mixins apply at classload, not compile time)
 * and the server has real players/bots depending on it, that risk isn't worth
 * loud-fail-by-default here — confirm it worked by testing an enchant in-game
 * after restart, or by grepping the log for a "0 targets"/conflict warning on
 * EnchantmentHelperMixin specifically, same as how LivingEntityEnchantMixin's
 * conflict was originally found.
 */
@Mixin(EnchantmentHelper.class)
public abstract class EnchantmentHelperMixin {

    @Inject(method = "getEquipmentLevel", at = @At("RETURN"), cancellable = true, require = 0)
    private static void prizmo_phantomEquipmentLevel(
            Enchantment enchantment, LivingEntity entity, CallbackInfoReturnable<Integer> cir) {
        if (!(entity instanceof ServerPlayerEntity player)) return;
        Identifier id = Registries.ENCHANTMENT.getId(enchantment);
        if (id == null || !EnchantTracker.getActiveSet(player.getUuid()).contains(id)) return;
        int phantomLevel = enchantment.getMaxLevel();
        if (phantomLevel > cir.getReturnValue()) cir.setReturnValue(phantomLevel);
    }
}
