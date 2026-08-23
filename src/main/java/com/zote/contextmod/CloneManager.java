package com.zote.contextmod;

import com.mojang.authlib.GameProfile;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Clone Manifestation — a flying FakePlayerEntity with the summoner's skin.
 *
 * ── BEHAVIOUR ─────────────────────────────────────────────────────────────────
 *   IDLE   Orbits the summoner at IDLE_RADIUS / IDLE_HEIGHT, rotating slowly.
 *   AGGRO  Locks onto the nearest non-player LivingEntity within AGGRO_RADIUS.
 *          Flies toward it at CLONE_SPEED blocks/tick (3D, ignores terrain).
 *   ATTACK When within ATTACK_RANGE, swings and deals CLONE_DAMAGE every
 *          ATTACK_COOLDOWN ticks. Target is sticky — clone won't switch while
 *          current target is still alive and within AGGRO_RADIUS * 2.
 *
 * ── APPEARANCE ────────────────────────────────────────────────────────────────
 *   Uses the summoner's actual GameProfile properties (Mojang skin textures)
 *   copied into a new GameProfile with a unique UUID and a "~"-suffixed name.
 *   The clone shows up in the tab list as a second copy of the summoner.
 *
 * ── HTTP API (GET) ────────────────────────────────────────────────────────────
 *   /summon-clone?player=X   → creates one flying clone for X
 *   /dismiss-clone?player=X  → removes it
 *   /clone-state?player=X    → {"active":bool,"target":"name"|null}
 */
public class CloneManager {

    // ── Tuning ────────────────────────────────────────────────────────────────

    private static final double IDLE_RADIUS   = 2.5;   // orbit distance (blocks)
    private static final double IDLE_HEIGHT   = 1.5;   // float height above player feet
    private static final double AGGRO_RADIUS  = 20.0;  // mob scan range (blocks)
    private static final double ATTACK_RANGE  = 1.8;   // attack distance (blocks)
    private static final double CLONE_SPEED   = 0.35;  // blocks/tick toward target
    private static final float  CLONE_DAMAGE  = 4.0f;  // 2 hearts per swing
    private static final int    ATTACK_COOLDOWN = 10;  // ticks between attacks (0.5 s)
    private static final double ORBIT_SPEED   = 0.018; // rad/tick idle orbit (~5.8 s/rev)

    // ── State ─────────────────────────────────────────────────────────────────

    private static class CloneState {
        final UUID cloneUUID;
        int  attackCooldown  = 0;
        UUID currentTargetUUID = null;

        CloneState(UUID cloneUUID) { this.cloneUUID = cloneUUID; }
    }

    private static final Map<UUID, CloneState> clones = new ConcurrentHashMap<>();
    private static long tickCount = 0;

    // ── Public API ────────────────────────────────────────────────────────────

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
