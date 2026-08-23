package com.zote.contextmod.ai;

import com.zote.contextmod.CloneManager;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Highest-priority target goal: if the clone's owner is currently being
 * attacked, the clone immediately switches its target to the attacker.
 * Hands off to MeleeAttackGoal once the target is set — meat-shield behavior.
 */
public class DefendOwnerGoal extends Goal {

    private final MobEntity clone;

    public DefendOwnerGoal(MobEntity clone) {
        this.clone = clone;
        this.setControls(EnumSet.of(Control.TARGET));
    }

    @Override
    public boolean canStart() {
        UUID ownerUuid = CloneManager.getOwnerUuid(clone);
        if (ownerUuid == null) return false;
        PlayerEntity owner = clone.getWorld().getPlayerByUuid(ownerUuid);
        if (owner == null || !owner.isAlive()) return false;

        LivingEntity attacker = owner.getAttacker();
        return attacker != null && attacker.isAlive() && attacker != clone
            && clone.getTarget() != attacker;
    }

    @Override
    public void start() {
        UUID ownerUuid = CloneManager.getOwnerUuid(clone);
        PlayerEntity owner = ownerUuid != null ? clone.getWorld().getPlayerByUuid(ownerUuid) : null;
        if (owner != null) clone.setTarget(owner.getAttacker());
    }

    @Override
    public boolean shouldContinue() {
        return false; // one-shot hand-off; MeleeAttackGoal/ActiveTargetGoal take it from here
    }
}
