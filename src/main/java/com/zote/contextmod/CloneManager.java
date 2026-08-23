package com.zote.contextmod;

import com.mojang.authlib.GameProfile;
import com.zote.contextmod.ai.DefendOwnerGoal;
import com.zote.contextmod.ai.FollowOwnerGoal;
import com.zote.contextmod.mixin.MobEntityAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.HuskEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Hand;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Two independent "clone" features, merged here because both were wired into
 * ContextMod.java under the same class name before NOX/Apollo reconciliation:
 *
 * ── GHOST CLONES (spawnClone/despawnAll/listClones/isClone/getOwnerUuid) ───────
 *   Real, server-authoritative vanilla Husk entities summoned at the player's
 *   side, tagged with the owner's UUID via Entity#getCommandTags() (no custom
 *   entity type or NBT schema needed). Vanilla pathfinding/combat AI, replaced
 *   with owner-following meat-shield goals. Costs 1 heart to summon.
 *
 * ── CLONE MANIFESTATION (summon/dismiss/getState/tick) ──────────────────────────
 *   A flying FakePlayerEntity with the summoner's actual skin (GameProfile
 *   properties copied, new UUID, "~"-suffixed name — shows up in the tab list
 *   as a second copy of the summoner). Manually tick-positioned (orbit while
 *   idle, fly at + attack the nearest non-player LivingEntity when aggroed) —
 *   no vanilla AI involved.
 *
 * ── HTTP API (GET), both features ───────────────────────────────────────────────
 *   /clone-spawn?player=X        → ghost clone: summon one
 *   /clone-despawn-all?player=X  → ghost clone: remove all owned by X
 *   /clone-list?player=X         → ghost clone: list X's active clones
 *   /summon-clone?player=X       → manifestation: create one flying clone for X
 *   /dismiss-clone?player=X      → manifestation: remove it
 *   /clone-state?player=X        → manifestation: {"active":bool,"target":"name"|null}
 */
public class CloneManager {

    // ── Ghost clone tuning ───────────────────────────────────────────────────────

    private static final String CLONE_TAG        = "prizmo_clone";
    private static final String OWNER_TAG_PREFIX = "prizmo_owner_";
    private static final float  SUMMON_COST      = 2.0f; // 1 heart
    private static final double SEARCH_RADIUS    = 128.0;

    // ── Manifestation tuning ─────────────────────────────────────────────────────

    private static final double IDLE_RADIUS   = 2.5;   // orbit distance (blocks)
    private static final double IDLE_HEIGHT   = 1.5;   // float height above player feet
    private static final double AGGRO_RADIUS  = 20.0;  // mob scan range (blocks)
    private static final double ATTACK_RANGE  = 1.8;   // attack distance (blocks)
    private static final double CLONE_SPEED   = 0.35;  // blocks/tick toward target
    private static final float  CLONE_DAMAGE  = 4.0f;  // 2 hearts per swing
    private static final int    ATTACK_COOLDOWN = 10;  // ticks between attacks (0.5 s)
    private static final double ORBIT_SPEED   = 0.018; // rad/tick idle orbit (~5.8 s/rev)

    // ── Manifestation state ──────────────────────────────────────────────────────

    private static class CloneState {
        final UUID cloneUUID;
        int  attackCooldown  = 0;
        UUID currentTargetUUID = null;

        CloneState(UUID cloneUUID) { this.cloneUUID = cloneUUID; }
    }

    private static final Map<UUID, CloneState> clones = new ConcurrentHashMap<>();
    private static long tickCount = 0;

    // ══════════════════════════════════════════════════════════════════════════
    // GHOST CLONES
    // ══════════════════════════════════════════════════════════════════════════

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

    // ══════════════════════════════════════════════════════════════════════════
    // CLONE MANIFESTATION
    // ══════════════════════════════════════════════════════════════════════════

    public static String summon(MinecraftServer server, String playerName) {
        return runOnServer(server, player -> {
            if (clones.containsKey(player.getUuid())) return "{\"error\":\"clone already active\"}";

            // Build GameProfile: new UUID/name, same skin textures as summoner
            String baseName = player.getName().getString();
            String cloneName = baseName.substring(0, Math.min(15, baseName.length())) + "~";
            GameProfile cloneProfile = new GameProfile(UUID.randomUUID(), cloneName);
            for (var entry : player.getGameProfile().getProperties().entries()) {
                cloneProfile.getProperties().put(entry.getKey(), entry.getValue());
            }

            ServerWorld world = player.getServerWorld();
            FakePlayerEntity clone = new FakePlayerEntity(server, world, cloneProfile);
            clone.setPosition(player.getX(), player.getY(), player.getZ());

            // Connect to server (sends initial packets through fake connection → discarded)
            FakeClientConnection conn = FakeClientConnection.create();
            new ServerPlayNetworkHandler(server, conn, clone); // sets clone.networkHandler
            server.getPlayerManager().onPlayerConnect(conn, clone);

            // Configure flight
            clone.setNoGravity(true);
            clone.setInvulnerable(true);
            clone.getAbilities().flying = false; // cosmetic — actual positioning is from tick

            clones.put(player.getUuid(), new CloneState(clone.getUuid()));
            return "{\"ok\":true,\"name\":\"" + cloneName + "\"}";
        }, playerName);
    }

    public static String dismiss(MinecraftServer server, String playerName) {
        return runOnServer(server, player -> {
            CloneState state = clones.remove(player.getUuid());
            if (state == null) return "{\"error\":\"no clone active\"}";
            discard(server, state);
            return "{\"ok\":true}";
        }, playerName);
    }

    public static String getState(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"active\":false}";
        CloneState state = clones.get(player.getUuid());
        if (state == null) return "{\"active\":false}";

        ServerWorld world = player.getServerWorld();
        Entity clone = world.getEntity(state.cloneUUID);
        if (clone == null || clone.isRemoved()) return "{\"active\":false}";

        String targetStr = "null";
        if (state.currentTargetUUID != null) {
            Entity t = world.getEntity(state.currentTargetUUID);
            if (t instanceof LivingEntity le && le.isAlive()) {
                targetStr = "\"" + le.getName().getString() + "\"";
            }
        }
        return "{\"active\":true,\"target\":" + targetStr + "}";
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public static void tick(MinecraftServer server) {
        if (clones.isEmpty()) { tickCount++; return; }
        double orbitAngle = tickCount * ORBIT_SPEED;
        tickCount++;

        for (Map.Entry<UUID, CloneState> entry : new ArrayList<>(clones.entrySet())) {
            UUID summUUID = entry.getKey();
            CloneState state = entry.getValue();

            ServerPlayerEntity summoner = server.getPlayerManager().getPlayer(summUUID);
            if (summoner == null || summoner.isRemoved()) {
                clones.remove(summUUID);
                continue;
            }

            ServerWorld world = summoner.getServerWorld();
            Entity clone = world.getEntity(state.cloneUUID);
            if (clone == null || clone.isRemoved()) {
                clones.remove(summUUID);
                continue;
            }

            if (state.attackCooldown > 0) state.attackCooldown--;

            Vec3d sumPos = summoner.getPos();

            // ── Resolve target ─────────────────────────────────────────────
            LivingEntity target = null;
            if (state.currentTargetUUID != null) {
                Entity e = world.getEntity(state.currentTargetUUID);
                if (e instanceof LivingEntity le && le.isAlive()
                        && le.squaredDistanceTo(sumPos) < (AGGRO_RADIUS * 2) * (AGGRO_RADIUS * 2)) {
                    target = le;
                } else {
                    state.currentTargetUUID = null;
                }
            }
            if (target == null) {
                target = findNearest(world, sumPos, clone.getUuid());
                state.currentTargetUUID = target != null ? target.getUuid() : null;
            }

            // ── Move ──────────────────────────────────────────────────────
            if (target != null) {
                Vec3d targetPos = new Vec3d(
                    target.getX(),
                    target.getY() + target.getHeight() * 0.5,
                    target.getZ()
                );
                Vec3d dir  = targetPos.subtract(clone.getPos());
                double dist = dir.length();

                if (dist > ATTACK_RANGE) {
                    double step = Math.min(CLONE_SPEED, dist - ATTACK_RANGE);
                    Vec3d n = dir.normalize();
                    clone.setPosition(
                        clone.getX() + n.x * step,
                        clone.getY() + n.y * step,
                        clone.getZ() + n.z * step
                    );
                }

                // Face toward target
                double dx = target.getX() - clone.getX();
                double dz = target.getZ() - clone.getZ();
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                clone.setYaw(yaw);
                if (clone instanceof ServerPlayerEntity sp) {
                    sp.setBodyYaw(yaw);
                    sp.setHeadYaw(yaw);
                }

                // Attack
                if (dist <= ATTACK_RANGE + 0.5 && state.attackCooldown <= 0
                        && clone instanceof FakePlayerEntity fp) {
                    fp.swingHand(Hand.MAIN_HAND, true);
                    target.damage(world.getDamageSources().playerAttack(fp), CLONE_DAMAGE);
                    state.attackCooldown = ATTACK_COOLDOWN;
                }

            } else {
                // Idle orbit around summoner
                double nx = sumPos.x + Math.cos(orbitAngle) * IDLE_RADIUS;
                double ny = sumPos.y + IDLE_HEIGHT;
                double nz = sumPos.z + Math.sin(orbitAngle) * IDLE_RADIUS;
                clone.setPosition(nx, ny, nz);

                // Face outward (tangential to orbit — point away from summoner)
                double dx = nx - sumPos.x;
                double dz = nz - sumPos.z;
                float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
                clone.setYaw(yaw);
                if (clone instanceof ServerPlayerEntity sp) {
                    sp.setBodyYaw(yaw);
                    sp.setHeadYaw(yaw);
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static LivingEntity findNearest(ServerWorld world, Vec3d centre, UUID excludeUUID) {
        Box box = new Box(
            centre.x - AGGRO_RADIUS, centre.y - AGGRO_RADIUS, centre.z - AGGRO_RADIUS,
            centre.x + AGGRO_RADIUS, centre.y + AGGRO_RADIUS, centre.z + AGGRO_RADIUS
        );
        List<LivingEntity> list = world.getEntitiesByClass(LivingEntity.class, box,
            e -> !e.isRemoved()
              && e.isAlive()
              && !(e instanceof PlayerEntity)
              && !(e instanceof ArmorStandEntity)
              && !e.getUuid().equals(excludeUUID)
        );
        LivingEntity nearest  = null;
        double       nearDist = Double.MAX_VALUE;
        for (LivingEntity e : list) {
            double d = e.squaredDistanceTo(centre.x, centre.y, centre.z);
            if (d < nearDist) { nearDist = d; nearest = e; }
        }
        return nearest;
    }

    private static void discard(MinecraftServer server, CloneState state) {
        // Use PlayerManager.remove() so the entity is properly despawned for all clients
        ServerPlayerEntity clone = server.getPlayerManager().getPlayer(state.cloneUUID);
        if (clone != null) {
            server.getPlayerManager().remove(clone);
        }
    }

    /** Resets manifestation state (ghost clones need no reset — they're vanilla mobs, re-queried from the world each call). */
    public static void onServerStart() {
        clones.clear();
        tickCount = 0;
    }

    @FunctionalInterface
    private interface PlayerAction { String run(ServerPlayerEntity player); }

    private static String runOnServer(MinecraftServer server, PlayerAction action, String playerName) {
        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
                if (player == null) { future.complete("{\"error\":\"player not found\"}"); return; }
                future.complete(action.run(player));
            } catch (Exception e) {
                future.complete("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }
}
