package com.zote.contextmod.mixin;

import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.mob.MobEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes MobEntity's protected goal/target selectors so CloneManager can
 * rewrite a freshly spawned mob's AI (vanilla goals are added in the
 * constructor, before any of our spawn-time tagging happens), and so
 * GauntletManager can inspect a mob's current target.
 */
@Mixin(MobEntity.class)
public interface MobEntityAccessor {

    @Accessor("goalSelector")
    GoalSelector getGoalSelector();

    @Accessor("targetSelector")
    GoalSelector getTargetSelector();

    @Accessor("targetSelector")
    GoalSelector prizmo_getTargetSelector();
}
