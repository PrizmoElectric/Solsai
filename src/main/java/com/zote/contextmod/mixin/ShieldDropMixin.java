package com.zote.contextmod.mixin;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Prevents ShieldManifest armor stands from dropping their shield item on death.
 * Without this, any shield that dies from damage would drop a shield item on the floor.
 */
@Mixin(LivingEntity.class)
public abstract class ShieldDropMixin {

    @Inject(method = "drop(Lnet/minecraft/entity/damage/DamageSource;)V",
            at = @At("HEAD"), cancellable = true)
    private void cancelShieldManifestDrops(DamageSource damageSource, CallbackInfo ci) {
        Text name = ((LivingEntity)(Object)this).getCustomName();
        if (name != null && "ShieldManifest".equals(name.getString())) {
            ci.cancel();
        }
    }
}
