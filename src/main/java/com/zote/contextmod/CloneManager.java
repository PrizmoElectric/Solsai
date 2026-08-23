package com.zote.contextmod;

import com.zote.contextmod.ai.DefendOwnerGoal;
import com.zote.contextmod.ai.FollowOwnerGoal;
import com.zote.contextmod.mixin.MobEntityAccessor;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.TypeFilter;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * "Ghost clones" — vanilla Husk entities summoned at the player's side.
 * No extra server connections (unlike the Nilo mineflayer clone army):
 * these are real, server-authoritative mobs with vanilla pathfinding/combat
 * AI, tagged with the owner's UUID so prizmo-system can render them and
 * Solsai can recall them on command.
 *
 * Ownership is tracked via Entity#getCommandTags() (persisted in vanilla
 * NBT automatically) — no custom entity type or NBT schema needed.
 */
public class CloneManager {

    private static final String CLONE_TAG        = "prizmo_clone";
    private static final String OWNER_TAG_PREFIX = "prizmo_owner_";
    private static final float  SUMMON_COST      = 2.0f; // 1 heart
    private static final double SEARCH_RADIUS    = 128.0;

    public static UUID getOwnerUuid(Entity entity) {
        for (String tag : entity.getCommandTags()) {
            if (tag.startsWith(OWNER_TAG_PREFIX)) {
                try {
                    return UUID.fromString(tag.substring(OWNER_TAG_PREFIX.length()));
                } catch (IllegalArgumentException ignored) {}
            }
        }
        return null;
    }

    public static boolean isClone(Entity entity) {
        return entity.getCommandTags().contains(CLONE_TAG);
    }

    /** Must run on the server thread. Returns a JSON result string. */
    public static String spawnClone(ServerPlayerEntity owner) {
        if (owner.getHealth() <= SUMMON_COST) {
            return "{\"error\":\"not enough health to summon a ghost (need more than 1 heart)\"}";
        }

        ServerWorld world = owner.getServerWorld();
        HuskEntity clone = EntityType.HUSK.create(world);
        if (clone == null) return "{\"error\":\"failed to create clone entity\"}";

        double angle = world.random.nextDouble() * Math.PI * 2;
        double dx = Math.cos(angle) * 1.5;
        double dz = Math.sin(angle) * 1.5;
        clone.refreshPositionAndAngles(owner.getX() + dx, owner.getY(), owner.getZ() + dz, owner.getYaw(), 0f);

        clone.setPersistent();
        clone.addCommandTag(CLONE_TAG);
        clone.addCommandTag(OWNER_TAG_PREFIX + owner.getUuid());

        clone.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
        clone.setEquipmentDropChance(EquipmentSlot.MAINHAND, 0f);
        clone.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, StatusEffectInstance.INFINITE, 0, false, false));

        configureGoals(clone);

        if (!world.spawnEntity(clone)) {
            return "{\"error\":\"world rejected spawn\"}";
        }

        owner.damage(world.getDamageSources().magic(), SUMMON_COST);
        world.spawnParticles(ParticleTypes.SOUL,
            owner.getX(), owner.getY() + 1.0, owner.getZ(),
            30, 0.3, 0.5, 0.3, 0.02);

        return "{\"success\":true,\"uuid\":\"" + clone.getUuid() + "\"}";
    }

    /** Replaces a vanilla Husk's default goals with owner-following meat-shield AI. */
    private static void configureGoals(HuskEntity clone) {
        MobEntityAccessor accessor = (MobEntityAccessor) (Object) clone;
        GoalSelector goals   = accessor.getGoalSelector();
        GoalSelector targets = accessor.getTargetSelector();

        goals.clear(g -> true);
        targets.clear(g -> true);

        goals.add(0, new SwimGoal(clone));
        goals.add(1, new MeleeAttackGoal(clone, 1.2, false));
        goals.add(2, new FollowOwnerGoal(clone, 1.0, 2.5f, 16f));
        goals.add(3, new WanderAroundFarGoal(clone, 0.8));
        goals.add(4, new LookAtEntityGoal(clone, PlayerEntity.class, 8f));
        goals.add(5, new LookAroundGoal(clone));

        targets.add(0, new DefendOwnerGoal(clone));
        targets.add(1, new ActiveTargetGoal<>(clone, HostileEntity.class, 10, true, false,
            entity -> !isClone(entity)));
    }

    /** Despawns all ghost clones owned by the given player (current world only). Returns the count removed. */
    public static int despawnAll(ServerPlayerEntity owner) {
        ServerWorld world = owner.getServerWorld();
        Box searchBox = boxAround(owner.getPos(), SEARCH_RADIUS);
        List<MobEntity> found = world.getEntitiesByType(TypeFilter.instanceOf(MobEntity.class), searchBox,
            e -> isClone(e) && owner.getUuid().equals(getOwnerUuid(e)));

        for (MobEntity e : found) e.discard();
        return found.size();
    }

    /** Returns a JSON array of {uuid,x,y,z,health,maxHealth,task} for the given player's ghost clones. */
    public static String listClones(ServerPlayerEntity owner) {
        ServerWorld world = owner.getServerWorld();
        Box searchBox = boxAround(owner.getPos(), SEARCH_RADIUS);
        List<LivingEntity> found = world.getEntitiesByType(TypeFilter.instanceOf(LivingEntity.class), searchBox,
            e -> isClone(e) && owner.getUuid().equals(getOwnerUuid(e)));

        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        for (LivingEntity e : found) {
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"uuid\":\"").append(e.getUuid()).append("\"")
              .append(",\"x\":").append(e.getX())
              .append(",\"y\":").append(e.getY())
              .append(",\"z\":").append(e.getZ())
              .append(",\"health\":").append(e.getHealth())
              .append(",\"maxHealth\":").append(e.getMaxHealth())
              .append(",\"task\":\"").append(taskOf(e, owner)).append("\"")
              .append("}");
        }
        sb.append("]");
        return sb.toString();
    }

    /** Derives a human-readable current activity for a clone: fighting/returning/following. */
    private static String taskOf(LivingEntity clone, ServerPlayerEntity owner) {
        if (clone instanceof MobEntity mob) {
            LivingEntity target = mob.getTarget();
            if (target != null) {
                return "fighting " + target.getName().getString();
            }
        }
        if (clone.getPos().distanceTo(owner.getPos()) > 3.5) {
            return "returning";
        }
        return "following";
    }

    private static Box boxAround(Vec3d center, double radius) {
        return new Box(center.x - radius, center.y - radius, center.z - radius,
                        center.x + radius, center.y + radius, center.z + radius);
    }
}
