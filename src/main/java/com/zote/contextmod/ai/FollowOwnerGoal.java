package com.zote.contextmod.ai;

import com.zote.contextmod.CloneManager;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.ai.goal.Goal;
import net.minecraft.entity.player.PlayerEntity;

import java.util.EnumSet;
import java.util.UUID;

/**
 * Tethers a ghost clone to its summoner. Reads the owner UUID from the
 * clone's command tags (set once at spawn time by CloneManager) since
 * vanilla mobs have no spare field for "owner".
 */
public class FollowOwnerGoal extends Goal {

    private final MobEntity clone;
    private final double speed;
    private final float minDistance;
    private final float teleportDistance;

    private PlayerEntity owner;
    private int recalcCooldown;

    public FollowOwnerGoal(MobEntity clone, double speed, float minDistance, float teleportDistance) {
        this.clone = clone;
        this.speed = speed;
        this.minDistance = minDistance;
        this.teleportDistance = teleportDistance;
        this.setControls(EnumSet.of(Control.MOVE, Control.LOOK));
    }

    private PlayerEntity findOwner() {
        UUID ownerUuid = CloneManager.getOwnerUuid(clone);
        if (ownerUuid == null) return null;
        return clone.getWorld().getPlayerByUuid(ownerUuid);
    }

    @Override
    public boolean canStart() {
        if (clone.getTarget() != null) return false;
        owner = findOwner();
        return owner != null && owner.isAlive()
            && clone.squaredDistanceTo(owner) > (double) (minDistance * minDistance);
    }

    @Override
    public boolean shouldContinue() {
        if (owner == null || !owner.isAlive() || clone.getTarget() != null) return false;
        return clone.squaredDistanceTo(owner) > (double) (minDistance * minDistance);
    }

    @Override
    public void start() {
        recalcCooldown = 0;
    }

    @Override
    public void stop() {
        owner = null;
        clone.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        clone.getLookControl().lookAt(owner, 30.0f, 30.0f);

        if (--recalcCooldown > 0) return;
        recalcCooldown = 10;

        if (clone.squaredDistanceTo(owner) > (double) (teleportDistance * teleportDistance)) {
            // Way too far behind (chunk unload, fall, etc.) — snap back to owner's side
            clone.teleport(owner.getX(), owner.getY(), owner.getZ());
        } else {
            clone.getNavigation().startMovingTo(owner, speed);
        }
    }
}
