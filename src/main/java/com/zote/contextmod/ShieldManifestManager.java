package com.zote.contextmod;

import net.minecraft.block.Block;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.entity.projectile.ProjectileEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Shield Manifestation — server-side dome using Fibonacci sphere distribution.
 *
 * ── DOME MODE (default) ───────────────────────────────────────────────────────
 *   Shields are placed on a Fibonacci sphere of radius ORBIT_RADIUS centred at
 *   player.y + ORBIT_CENTER_Y_OFFSET.  A slow Y-axis rotation sweeps any gaps.
 *   Adding a shield redistributes the whole set — no overlap ever.
 *
 * ── FOCUS MODE ────────────────────────────────────────────────────────────────
 *   Activated via focus(). Two sub-modes:
 *
 *   STACKED — all shields converge to a single point in the focus direction.
 *     Maximum concentration on one vector. Every shield blocks the same spot.
 *
 *   DISTRIBUTED — shields spread in a rectangular grid on the sphere surface,
 *     centred on the focus direction and curved to follow the sphere.
 *     Grid is cols × rows (closest square to √N), each row and column centred.
 *     Shields are placed side-by-side at FOCUS_SPACING radians apart.
 *     For N=6: 3-wide × 2-tall curved wall.  For N=12: 4×3.
 *
 *   Focus direction:
 *     track=false → player's look direction at the moment focus() is called.
 *     track=true  → continuously tracks the nearest non-player living entity;
 *                   falls back to stored direction if none found.
 *
 * ── SPLIT MODE ────────────────────────────────────────────────────────────────
 *   splitSummon() consumes a vanilla shield from inventory, reads its remaining
 *   durability as a shared pool, and creates N manifestations drawing from it.
 *   Each blocked projectile costs max(1, proj.getDamage()) from the pool.
 *   When pool ≤ 0 all shields break simultaneously.
 *
 * ── TUNING ────────────────────────────────────────────────────────────────────
 *   ORBIT_RADIUS           2.5   blocks from player centre
 *   ORBIT_CENTER_Y_OFFSET  0.9   above player feet (mid-body)
 *   ROT_SPEED              0.010 rad/tick Y-rotation in dome mode (~10.5 s/rev)
 *   PROJ_RADIUS            1.6   projectile-discard sphere per shield
 *   PUSH_RADIUS            1.8   mob-push sphere per shield
 *   FOCUS_SPACING          0.80  rad between adjacent shields in distributed mode
 *
 * ── HTTP API (GET) ────────────────────────────────────────────────────────────
 *   /manifest-shield?player=X
 *   /manifest-dome?player=X&count=N
 *   /split-shield?player=X&count=N
 *   /dismiss-shields?player=X
 *   /shield-focus?player=X&mode=stacked|distributed&track=true|false
 *   /shield-unfocus?player=X
 *   /shield-state?player=X   → {"count":N,"split":bool[,"pool":K],"focus":{...}}
 */
public class ShieldManifestManager {

    // ── Tuning ────────────────────────────────────────────────────────────────

    private static final double ORBIT_RADIUS          = 2.5;
    private static final double ORBIT_CENTER_Y_OFFSET = 0.9;
    private static final double ROT_SPEED             = 0.010; // rad/tick dome rotation
    private static final double PROJ_RADIUS           = 1.6;
    private static final double PUSH_RADIUS           = 1.8;
    private static final double PUSH_FORCE            = 0.45;
    private static final double PUSH_LIFT             = 0.18;
    private static final int    DURABILITY_PER_BLOCK  = 3;
    private static final double FOCUS_SPACING         = 0.80; // rad between shields in distributed
    private static final double FOCUS_TRACK_RADIUS    = 24.0; // blocks to scan for nearest target

    private static final String SHIELD_NAME  = "ShieldManifest";
    private static final double GOLDEN_ANGLE = Math.PI * (3.0 - Math.sqrt(5.0)); // ≈ 2.399 rad

    // ── Data model ────────────────────────────────────────────────────────────

    enum FocusMode { STACKED, DISTRIBUTED }

    private static class ShieldInstance {
        final UUID standUUID;
        BlockPos lastBlockPos = null;
        float lastHealth = -1f; // -1 = uninitialised; updated each tick for pool drain tracking
        // Per-shield durability pool: Integer.MAX_VALUE = legacy/split mode (no individual limit)
        int durabilityPool = Integer.MAX_VALUE;
        ShieldInstance(UUID u) { this.standUUID = u; }
    }

    private static class ShieldSet {
        final List<ShieldInstance> shields = new ArrayList<>();

        // Split-mode durability pool (Integer.MAX_VALUE = infinite / dome mode)
        int sharedPool = Integer.MAX_VALUE;
        boolean isSplit() { return sharedPool != Integer.MAX_VALUE; }

        // Focus
        boolean   focusActive  = false;
        boolean   trackNearest = false;
        FocusMode focusMode    = FocusMode.DISTRIBUTED;
        Vec3d     focusDir     = null; // null until first hostile found or look-dir captured
    }

    private static final Map<UUID, ShieldSet> playerShields = new ConcurrentHashMap<>();
    private static long tickCount = 0;

    // ── Fibonacci sphere ──────────────────────────────────────────────────────

    /** Unit direction for shield i of N on a Fibonacci sphere, rotated by globalAngle around Y. */
    private static Vec3d fibPoint(int i, int total, double globalAngle) {
        double y      = 1.0 - (2.0 * i + 1.0) / total;
        double rHoriz = Math.sqrt(Math.max(0.0, 1.0 - y * y));
        double theta  = GOLDEN_ANGLE * i + globalAngle;
        return new Vec3d(Math.cos(theta) * rHoriz, y, Math.sin(theta) * rHoriz);
    }

    // ── Rodrigues rotation ────────────────────────────────────────────────────

    /**
     * Rotate 'vec' around 'axis' (unit vector) by 'angle' radians.
     * Used by positionDistributed to map grid offsets onto the sphere surface.
     */
    private static Vec3d rotateAround(Vec3d vec, Vec3d axis, double angle) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        // k·v (dot product)
        double dot = axis.x * vec.x + axis.y * vec.y + axis.z * vec.z;
        // k×v (cross product)
        double cx = axis.y * vec.z - axis.z * vec.y;
        double cy = axis.z * vec.x - axis.x * vec.z;
        double cz = axis.x * vec.y - axis.y * vec.x;
        return new Vec3d(
            vec.x * cos + cx * sin + axis.x * dot * (1.0 - cos),
            vec.y * cos + cy * sin + axis.y * dot * (1.0 - cos),
            vec.z * cos + cz * sin + axis.z * dot * (1.0 - cos)
        );
    }

    // ── Focus positioning ─────────────────────────────────────────────────────

    /**
     * Place all shields in a rectangular grid curved to the sphere surface,
     * centred on focusDir.  Each shield is FOCUS_SPACING radians from its
     * neighbour.  Last row is centred if it has fewer shields than the others.
     *
     * Grid sizing: cols = ceil(sqrt(N)), rows = ceil(N / cols).
     * Examples:  N=2 → 2×1   N=3 → 2×2(tri)   N=6 → 3×2   N=12 → 4×3
     */
    private static void positionDistributed(
            ServerWorld world, List<ShieldInstance> shields, Vec3d centre, Vec3d focusDir) {

        int N    = shields.size();
        int cols = Math.max(1, (int) Math.ceil(Math.sqrt(N)));
        int rows = (int) Math.ceil((double) N / cols);

        // Build local coordinate frame around focusDir
        Vec3d worldUp = new Vec3d(0.0, 1.0, 0.0);
        // right: perpendicular to focus in the horizontal plane
        Vec3d right;
        double dot = focusDir.x * worldUp.x + focusDir.y * worldUp.y + focusDir.z * worldUp.z;
        if (Math.abs(dot) > 0.99) {
            // Near-vertical focus — use world X as fallback for right
            right = new Vec3d(1.0, 0.0, 0.0);
        } else {
            // right = worldUp × focusDir, then normalise
            double rx = worldUp.y * focusDir.z - worldUp.z * focusDir.y;
            double ry = worldUp.z * focusDir.x - worldUp.x * focusDir.z;
            double rz = worldUp.x * focusDir.y - worldUp.y * focusDir.x;
            double rLen = Math.sqrt(rx*rx + ry*ry + rz*rz);
            right = new Vec3d(rx / rLen, ry / rLen, rz / rLen);
        }
        // localUp = focusDir × right (points "up" in the focus frame)
        double ux = focusDir.y * right.z - focusDir.z * right.y;
        double uy = focusDir.z * right.x - focusDir.x * right.z;
        double uz = focusDir.x * right.y - focusDir.y * right.x;
        double uLen = Math.sqrt(ux*ux + uy*uy + uz*uz);
        Vec3d localUp = new Vec3d(ux / uLen, uy / uLen, uz / uLen);

        int idx = 0;
        for (int row = 0; row < rows && idx < N; row++) {
            int shieldsInRow = Math.min(cols, N - row * cols);
            double vAngle = (row - (rows - 1) * 0.5) * FOCUS_SPACING;

            for (int col = 0; col < shieldsInRow && idx < N; col++, idx++) {
                double hAngle = (col - (shieldsInRow - 1) * 0.5) * FOCUS_SPACING;

                // Rotate focusDir: first horizontally (around localUp), then vertically (around right)
                Vec3d dir = rotateAround(focusDir, localUp, hAngle);
                dir       = rotateAround(dir,      right,   vAngle);
                // Normalise (Rodrigues preserves length but floating-point drift accumulates)
                double len = Math.sqrt(dir.x*dir.x + dir.y*dir.y + dir.z*dir.z);
                dir = new Vec3d(dir.x / len, dir.y / len, dir.z / len);

                ShieldInstance shInst = shields.get(idx);
                Entity shield = world.getEntity(shInst.standUUID);
                if (shield != null && !shield.isRemoved()) {
                    double nx = centre.x + dir.x * ORBIT_RADIUS;
                    double ny = centre.y + dir.y * ORBIT_RADIUS;
                    double nz = centre.z + dir.z * ORBIT_RADIUS;
                    shield.setPosition(nx, ny, nz);
                    faceOutward(shield, centre);
                    updateShieldBlock(world, shInst, nx, ny, nz);
                }
            }
        }
    }

    /**
     * Rotate the ArmorStand to face away from centre (outward on the sphere).
     * Sets yaw, bodyYaw, and headYaw so the shield face points away from the player.
     * Minecraft yaw: 0=south, 90=west, 180=north, -90=east → atan2(-dx, dz).
     */
    private static void faceOutward(Entity e, Vec3d centre) {
        double dx = e.getX() - centre.x;
        double dz = e.getZ() - centre.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        e.setYaw(yaw);
        if (e instanceof LivingEntity le) {
            le.setBodyYaw(yaw);
            le.setHeadYaw(yaw);
        }
    }

    /** Nearest non-player, non-ArmorStand living entity within FOCUS_TRACK_RADIUS. */
    private static Entity findNearestHostile(ServerWorld world, Vec3d centre) {
        Box box = new Box(
            centre.x - FOCUS_TRACK_RADIUS, centre.y - FOCUS_TRACK_RADIUS, centre.z - FOCUS_TRACK_RADIUS,
            centre.x + FOCUS_TRACK_RADIUS, centre.y + FOCUS_TRACK_RADIUS, centre.z + FOCUS_TRACK_RADIUS
        );
        List<LivingEntity> candidates = world.getEntitiesByClass(
            LivingEntity.class, box,
            e -> !e.isRemoved() && !(e instanceof PlayerEntity) && !(e instanceof ArmorStandEntity)
        );
        Entity nearest  = null;
        double nearDist = Double.MAX_VALUE;
        for (LivingEntity e : candidates) {
            double dx = e.getX() - centre.x, dy = e.getY() - centre.y, dz = e.getZ() - centre.z;
            double d  = dx*dx + dy*dy + dz*dz;
            if (d < nearDist) { nearDist = d; nearest = e; }
        }
        return nearest;
    }

    // ── Shield block placement ────────────────────────────────────────────────

    /**
     * Place or move the invisible ShieldBlock to follow the ArmorStand.
     * Only acts when the ArmorStand crosses a block boundary, so most ticks are no-ops.
     * Does not overwrite non-air blocks (e.g. player-placed walls).
     */
    private static void updateShieldBlock(ServerWorld world, ShieldInstance inst,
                                          double x, double y, double z) {
        BlockPos newPos = BlockPos.ofFloored(x, y, z);
        if (newPos.equals(inst.lastBlockPos)) return;
        if (inst.lastBlockPos != null
                && world.getBlockState(inst.lastBlockPos).isOf(ContextMod.SHIELD_BLOCK)) {
            world.removeBlock(inst.lastBlockPos, false);
        }
        if (world.getBlockState(newPos).isAir()) {
            world.setBlockState(newPos, ContextMod.SHIELD_BLOCK.getDefaultState(),
                Block.NOTIFY_LISTENERS | Block.SKIP_DROPS);
        }
        inst.lastBlockPos = newPos;
    }

    /** Remove the shield block for one instance (on dismiss or entity removal). */
    private static void removeShieldBlock(ServerWorld world, ShieldInstance inst) {
        if (inst.lastBlockPos != null
                && world.getBlockState(inst.lastBlockPos).isOf(ContextMod.SHIELD_BLOCK)) {
            world.removeBlock(inst.lastBlockPos, false);
        }
        inst.lastBlockPos = null;
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    /**
     * Try to consume one vanilla shield from player inventory.
     * Returns the shield's remaining durability, or -1 if no shield was found.
     */
    private static int consumeShieldFromInventory(ServerPlayerEntity player) {
        var inv = player.getInventory();
        for (int s = 0; s < inv.size(); s++) {
            ItemStack st = inv.getStack(s);
            if (st.getItem() == Items.SHIELD) {
                int remaining = Math.max(10, st.getMaxDamage() - st.getDamage());
                inv.removeStack(s, 1);
                inv.markDirty();
                return remaining;
            }
        }
        return -1;
    }

    /**
     * Spawn shields that each consume one inventory shield for their durability.
     * Fallback: drains hunger (EXHAUSTION) and gives 100 durability per shield.
     */
    private static String spawnShieldsAuto(ServerPlayerEntity player, int count) {
        UUID playerUUID   = player.getUuid();
        ShieldSet set     = playerShields.computeIfAbsent(playerUUID, k -> new ShieldSet());
        ServerWorld world = player.getServerWorld();

        for (int n = 0; n < count; n++) {
            int pool = consumeShieldFromInventory(player);
            if (pool < 0) {
                player.addExhaustion(3.0f);
                pool = 100;
            }

            ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            stand.setPosition(player.getX(), player.getY() + ORBIT_CENTER_Y_OFFSET, player.getZ());
            stand.setNoGravity(true);
            stand.setCustomName(Text.literal(SHIELD_NAME));
            stand.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            world.spawnEntity(stand);

            ShieldInstance inst = new ShieldInstance(stand.getUuid());
            inst.durabilityPool = pool;
            set.shields.add(inst);
        }

        int total = computeTotalDurability(set);
        String durStr = total < 0 ? "\"inf\"" : String.valueOf(total);
        return "{\"ok\":true,\"count\":" + set.shields.size() + ",\"totalDurability\":" + durStr + "}";
    }

    /** Returns total durability across non-split shields, or -1 if any have infinite pools. */
    private static int computeTotalDurability(ShieldSet set) {
        if (set.isSplit()) return set.sharedPool;
        int total = 0;
        for (ShieldInstance inst : set.shields) {
            if (inst.durabilityPool == Integer.MAX_VALUE) return -1;
            total += inst.durabilityPool;
        }
        return total;
    }

    // ── Internal spawn (used by splitSummon — must run on server thread) ─────

    private static String spawnShields(ServerPlayerEntity player, int count, int pool) {
        UUID playerUUID   = player.getUuid();
        ShieldSet set     = playerShields.computeIfAbsent(playerUUID, k -> new ShieldSet());
        ServerWorld world = player.getServerWorld();

        if (pool != Integer.MAX_VALUE && !set.isSplit()) set.sharedPool = pool;

        for (int n = 0; n < count; n++) {
            ArmorStandEntity stand = new ArmorStandEntity(EntityType.ARMOR_STAND, world);
            stand.setPosition(player.getX(), player.getY() + ORBIT_CENTER_Y_OFFSET, player.getZ());
            stand.setNoGravity(true);
            stand.setCustomName(Text.literal(SHIELD_NAME));
            stand.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
            world.spawnEntity(stand);
            set.shields.add(new ShieldInstance(stand.getUuid()));
        }

        String poolStr = set.isSplit()
            ? ",\"pool\":" + set.sharedPool + ",\"split\":true"
            : ",\"split\":false";
        return "{\"ok\":true,\"count\":" + set.shields.size() + poolStr + "}";
    }

    // ── Public API (HTTP thread — dispatch to server thread) ──────────────────

    public static String summon(MinecraftServer server, String playerName) {
        return runOnServer(server, p -> spawnShieldsAuto(p, 1), playerName);
    }

    public static String summonDome(MinecraftServer server, String playerName, int count) {
        return runOnServer(server, p -> spawnShieldsAuto(p, count), playerName);
    }

    public static String splitSummon(MinecraftServer server, String playerName, int count) {
        return runOnServer(server, player -> {
            if (count < 2) return "{\"error\":\"count must be >= 2\"}";
            PlayerInventory inv = player.getInventory();
            ItemStack shieldStack = null;
            int slot = -1;
            for (int s = 0; s < inv.size(); s++) {
                ItemStack st = inv.getStack(s);
                if (st.getItem() == Items.SHIELD) { shieldStack = st; slot = s; break; }
            }
            if (shieldStack == null) return "{\"error\":\"no shield in inventory\"}";
            int pool = Math.max(10, shieldStack.getMaxDamage() - shieldStack.getDamage());
            inv.removeStack(slot, 1);
            inv.markDirty();
            return spawnShields(player, count, pool);
        }, playerName);
    }

    /**
     * Activate focus mode.
     * mode:  "stacked" | "distributed"  (default distributed)
     * track: true = continuously track nearest living entity
     *        false = capture player's look direction once
     */
    public static String focus(MinecraftServer server, String playerName, String mode, boolean track) {
        return runOnServer(server, player -> {
            UUID playerUUID = player.getUuid();
            ShieldSet set   = playerShields.get(playerUUID);
            if (set == null || set.shields.isEmpty()) return "{\"error\":\"no shields active\"}";

            set.focusActive  = true;
            set.trackNearest = track;
            set.focusMode    = "stacked".equalsIgnoreCase(mode) ? FocusMode.STACKED : FocusMode.DISTRIBUTED;

            if (!track) {
                // Capture the player's current look direction
                double yaw   = Math.toRadians(player.getYaw());
                double pitch = Math.toRadians(player.getPitch());
                set.focusDir = new Vec3d(
                    -Math.sin(yaw) * Math.cos(pitch),
                    -Math.sin(pitch),
                     Math.cos(yaw) * Math.cos(pitch)
                );
                // Normalise (should already be unit, but guard floating-point)
                double l = Math.sqrt(set.focusDir.x*set.focusDir.x
                                   + set.focusDir.y*set.focusDir.y
                                   + set.focusDir.z*set.focusDir.z);
                set.focusDir = new Vec3d(set.focusDir.x/l, set.focusDir.y/l, set.focusDir.z/l);
            }
            // If track=true, focusDir stays null until first tick finds a target

            String modeStr = set.focusMode.name().toLowerCase();
            return "{\"ok\":true,\"mode\":\"" + modeStr + "\",\"tracking\":" + track + "}";
        }, playerName);
    }

    /** Return to Fibonacci dome. */
    public static void unfocus(MinecraftServer server, String playerName) {
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
            if (player == null) return;
            ShieldSet set = playerShields.get(player.getUuid());
            if (set == null) return;
            set.focusActive  = false;
            set.trackNearest = false;
            set.focusDir     = null;
        });
    }

    public static void dismissAll(MinecraftServer server, String playerName) {
        server.execute(() -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
            if (player == null) return;
            ShieldSet set = playerShields.remove(player.getUuid());
            if (set != null) discardSet(player.getServerWorld(), set);
        });
    }

    public static String getState(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"count\":0,\"totalDurability\":0,\"split\":false,\"focus\":{\"active\":false}}";
        ShieldSet set = playerShields.get(player.getUuid());
        if (set == null) return "{\"count\":0,\"totalDurability\":0,\"split\":false,\"focus\":{\"active\":false}}";

        int total = computeTotalDurability(set);
        String durStr  = total < 0 ? "\"inf\"" : String.valueOf(total);
        String poolStr = set.isSplit()
            ? ",\"totalDurability\":" + durStr + ",\"pool\":" + set.sharedPool + ",\"split\":true"
            : ",\"totalDurability\":" + durStr + ",\"split\":false";
        String focusStr = set.focusActive
            ? ",\"focus\":{\"active\":true,\"mode\":\"" + set.focusMode.name().toLowerCase()
                + "\",\"tracking\":" + set.trackNearest + "}"
            : ",\"focus\":{\"active\":false}";
        return "{\"count\":" + set.shields.size() + poolStr + focusStr + "}";
    }

    // ── Server tick ───────────────────────────────────────────────────────────

    public static void tick(MinecraftServer server) {
        double globalAngle = tickCount * ROT_SPEED;
        tickCount++;
        if (playerShields.isEmpty()) return;

        for (Map.Entry<UUID, ShieldSet> entry : new ArrayList<>(playerShields.entrySet())) {
            UUID playerUUID = entry.getKey();
            ShieldSet set   = entry.getValue();
            if (set.shields.isEmpty()) { playerShields.remove(playerUUID); continue; }

            ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerUUID);
            if (player == null || player.isRemoved()) continue;

            ServerWorld world  = player.getServerWorld();
            Vec3d centre = new Vec3d(player.getX(), player.getY() + ORBIT_CENTER_Y_OFFSET, player.getZ());

            // ── Step 1: Position shields ───────────────────────────────────
            // Also prune stale UUIDs here so Step 2 doesn't see them.
            set.shields.removeIf(inst -> {
                Entity e = world.getEntity(inst.standUUID);
                boolean stale = e == null || e.isRemoved();
                if (stale) removeShieldBlock(world, inst);
                return stale;
            });
            if (set.shields.isEmpty()) { playerShields.remove(playerUUID); continue; }
            int total = set.shields.size();

            if (set.focusActive) {
                // Resolve current focus direction
                if (set.trackNearest) {
                    Entity target = findNearestHostile(world, centre);
                    if (target != null) {
                        Vec3d targetCentre = new Vec3d(
                            target.getX(),
                            target.getY() + target.getHeight() * 0.5,
                            target.getZ()
                        );
                        Vec3d raw = new Vec3d(
                            targetCentre.x - centre.x,
                            targetCentre.y - centre.y,
                            targetCentre.z - centre.z
                        );
                        double l = Math.sqrt(raw.x*raw.x + raw.y*raw.y + raw.z*raw.z);
                        if (l > 0.01) set.focusDir = new Vec3d(raw.x/l, raw.y/l, raw.z/l);
                    }
                }
                // Fallback: player look direction if no target was ever found
                if (set.focusDir == null) {
                    double yaw   = Math.toRadians(player.getYaw());
                    double pitch = Math.toRadians(player.getPitch());
                    set.focusDir = new Vec3d(
                        -Math.sin(yaw) * Math.cos(pitch),
                        -Math.sin(pitch),
                         Math.cos(yaw) * Math.cos(pitch)
                    );
                }
                Vec3d fDir = set.focusDir;

                if (set.focusMode == FocusMode.STACKED) {
                    // All shields to one point, all facing outward (same direction = focusDir)
                    double sx = centre.x + fDir.x * ORBIT_RADIUS;
                    double sy = centre.y + fDir.y * ORBIT_RADIUS;
                    double sz = centre.z + fDir.z * ORBIT_RADIUS;
                    for (ShieldInstance inst : set.shields) {
                        Entity e = world.getEntity(inst.standUUID);
                        if (e != null) {
                            e.setPosition(sx, sy, sz);
                            faceOutward(e, centre);
                            updateShieldBlock(world, inst, sx, sy, sz);
                        }
                    }
                } else {
                    // Curved grid on sphere cap
                    positionDistributed(world, set.shields, centre, fDir);
                }

            } else {
                // Fibonacci dome with slow Y-rotation
                for (int i = 0; i < total; i++) {
                    ShieldInstance inst = set.shields.get(i);
                    Entity shield = world.getEntity(inst.standUUID);
                    if (shield == null) continue;
                    Vec3d dir = fibPoint(i, total, globalAngle);
                    double nx = centre.x + dir.x * ORBIT_RADIUS;
                    double ny = centre.y + dir.y * ORBIT_RADIUS;
                    double nz = centre.z + dir.z * ORBIT_RADIUS;
                    shield.setPosition(nx, ny, nz);
                    faceOutward(shield, centre);
                    updateShieldBlock(world, inst, nx, ny, nz);
                }
            }

            // ── Step 2a: Health tracking — drain pool from direct damage ──────────
            // Compares each shield's health to the previous tick. Any drop in HP
            // is subtracted from the split pool (or just removes the shield in dome mode
            // via natural death → pruning).  Initialises lastHealth on first seen tick.
            if (set.isSplit()) {
                for (ShieldInstance inst : set.shields) {
                    Entity shEnt = world.getEntity(inst.standUUID);
                    if (!(shEnt instanceof LivingEntity le)) continue;
                    float hp = le.getHealth();
                    if (inst.lastHealth >= 0f && hp < inst.lastHealth) {
                        set.sharedPool -= (int) Math.ceil(inst.lastHealth - hp);
                    }
                    inst.lastHealth = hp;
                }
                if (set.sharedPool <= 0) {
                    playerShields.remove(playerUUID);
                    discardSet(world, set);
                    continue;
                }
            } else {
                // Dome mode: just initialise lastHealth so it's ready if mode changes later.
                for (ShieldInstance inst : set.shields) {
                    Entity shEnt = world.getEntity(inst.standUUID);
                    if (shEnt instanceof LivingEntity le && inst.lastHealth < 0f) {
                        inst.lastHealth = le.getHealth();
                    }
                }
            }

            // ── Step 2b: Projectile blocking + mob pushing ─────────────────
            boolean drained = false;
            for (ShieldInstance inst : set.shields) {
                Entity shieldEnt = world.getEntity(inst.standUUID);
                if (shieldEnt == null || shieldEnt.isRemoved()) continue;
                Vec3d pos = shieldEnt.getPos();

                // Discard incoming projectiles and drain this shield's durability
                Box projBox = new Box(
                    pos.x - PROJ_RADIUS, pos.y - PROJ_RADIUS, pos.z - PROJ_RADIUS,
                    pos.x + PROJ_RADIUS, pos.y + PROJ_RADIUS, pos.z + PROJ_RADIUS
                );
                for (ProjectileEntity proj : world.getEntitiesByClass(
                        ProjectileEntity.class, projBox, p -> !p.isRemoved())) {
                    int cost = DURABILITY_PER_BLOCK;
                    if (proj instanceof PersistentProjectileEntity) {
                        cost = (int) Math.max(1, Math.ceil(
                            ((PersistentProjectileEntity) proj).getDamage()));
                    }
                    proj.discard();
                    if (set.isSplit()) {
                        set.sharedPool -= cost;
                        if (set.sharedPool <= 0) { drained = true; break; }
                    } else if (inst.durabilityPool != Integer.MAX_VALUE) {
                        inst.durabilityPool -= cost;
                        if (inst.durabilityPool <= 0) {
                            // Break this individual shield — discard entity, pruned next tick
                            shieldEnt.discard();
                            removeShieldBlock(world, inst);
                            break;
                        }
                    }
                }
                if (drained) break;

                // Push mobs
                Box pushBox = new Box(
                    pos.x - PUSH_RADIUS, pos.y - PUSH_RADIUS, pos.z - PUSH_RADIUS,
                    pos.x + PUSH_RADIUS, pos.y + PUSH_RADIUS, pos.z + PUSH_RADIUS
                );
                for (LivingEntity mob : world.getEntitiesByClass(
                        LivingEntity.class, pushBox,
                        e -> !e.isRemoved()
                          && !(e instanceof PlayerEntity)
                          && !(e instanceof ArmorStandEntity))) {
                    Vec3d d   = mob.getPos().subtract(pos);
                    double hl = d.horizontalLength();
                    if (hl < 0.01) d = new Vec3d(1.0, 0.0, 0.0);
                    else d = new Vec3d(d.x / hl, 0.0, d.z / hl);
                    mob.addVelocity(d.x * PUSH_FORCE, PUSH_LIFT, d.z * PUSH_FORCE);
                    mob.velocityModified = true;
                }
            }

            if (drained) {
                playerShields.remove(playerUUID);
                discardSet(world, set);
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void discardSet(ServerWorld world, ShieldSet set) {
        for (ShieldInstance inst : set.shields) {
            Entity e = world.getEntity(inst.standUUID);
            if (e != null) e.discard();
            removeShieldBlock(world, inst);
        }
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
                future.complete("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }

    public static void onServerStart() {
        playerShields.clear();
        tickCount = 0;
    }
}
