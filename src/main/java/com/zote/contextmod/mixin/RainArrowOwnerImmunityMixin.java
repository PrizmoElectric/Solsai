package com.zote.contextmod.mixin;

import com.zote.contextmod.ArrowManifestManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Prevents Arrow Rain arrows from hitting their own summoner.
 *
 * Confirmed via javap against the actual mapped 1.20.1 (Yarn build.10) ProjectileEntity class
 * — NOT assumed — that vanilla's owner-exclusion is temporary, not permanent: ProjectileEntity
 * has an ownerUuid/owner/leftOwner field set, and leftOwner flips true once the projectile is
 * judged to have travelled far enough from its owner's hitbox — after which canHit(Entity)
 * allows the owner again. This is the same mechanism behind the well-known vanilla behavior of
 * an arrow shot straight up falling back and hitting the shooter. Rain arrows spawn
 * RAIN_SPAWN_HEIGHT (15 blocks) above the caster, so leftOwner flips almost immediately —
 * meaning a falling Rain arrow can otherwise hit the summoner on the way down under plain
 * vanilla rules, especially in self-target mode where they're falling toward the caster's own
 * position on purpose. This is the exact class of bug already found and fixed once this
 * session for Barrage (a different mechanism — noClip toggling — but the same underlying
 * lesson: don't assume arrows can't hit their owner, verify it).
 *
 * Targets canHit(Entity), not onEntityHit(EntityHitResult) — this stops the collision from
 * resolving as a hit at all, rather than allowing the hit and cancelling damage after (which
 * could still trigger stop/knockback side effects). Scoped to RAIN_ARROW_TAG specifically, so
 * this never changes behavior for any other projectile in the game, including this project's
 * own Barrage/Circles/Mage-Circle arrows (which never target their owner's own position in the
 * first place, so don't need it).
 *
 * require=0, matching EnchantmentHelperMixin's own documented reasoning in this same package:
 * the project default (solsai.mixins.json's defaultRequire=1) fails the WHOLE mod's load if a
 * target doesn't resolve, which on a live server means it doesn't come back up on its next
 * restart. That's too large a blast radius for one safety mixin — if this one fails to apply,
 * confirm via a "0 targets"/conflict warning for RainArrowOwnerImmunityMixin in the log after a
 * restart, same diagnostic EnchantmentHelperMixin's conflict was originally found with.
 */
@Mixin(ProjectileEntity.class)
public abstract class RainArrowOwnerImmunityMixin {

    @Inject(method = "canHit(Lnet/minecraft/entity/Entity;)Z", at = @At("HEAD"),
            cancellable = true, require = 0)
    private void prizmo_neverHitRainOwner(Entity entity, CallbackInfoReturnable<Boolean> cir) {
        ProjectileEntity self = (ProjectileEntity) (Object) this;
        if (entity == null || entity != self.getOwner()) return;
        if (!self.getCommandTags().contains(ArrowManifestManager.RAIN_ARROW_TAG)) return;
        cir.setReturnValue(false);
    }
}
