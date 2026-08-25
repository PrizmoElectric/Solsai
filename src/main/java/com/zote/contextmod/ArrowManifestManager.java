package com.zote.contextmod;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Arrow Manifestation — arrows float in one or more rotating "circles" in front of the player,
 * all pointing at the crosshair. On shoot, all are launched in the look direction.
 *
 * Formation (the "mage spell circle" look):
 *   - Center: player eye + look * FORWARD_DIST (always in front — never behind the player)
 *   - Arrows are dealt round-robin across `totalCircles` formations, one more circle unlocking
 *     every ARROWS_PER_CIRCLE arrows (see circleCountFor()) — so the shape grows richer as more
 *     arrows accumulate instead of just packing one ring denser.
 *   - Circle 0 is always a plain round ring facing the player like a target reticle.
 *   - Circle 1 (once unlocked) is a "+" cross — arrows sit on 4 spokes through the center — and
 *     circle 2 is an "X" cross, spokes rotated 45°. The two crosses spin in OPPOSITE directions
 *     from each other, layered with the round circles rather than replacing them.
 *   - Circle 3 and up (round again) DIVERGE: each one's plane is tilted away from the player's
 *     straight-ahead look by DIVERGE_TILT, fanned around the look axis at the golden angle so
 *     successive circles don't all tilt the same way — overlapping tilted rings around one shared
 *     center, like a flower/mandala, rather than flat concentric circles.
 *   - The 6th circle (index 5) is special: instead of sitting still at the aim point like the
 *     others, its whole formation orbits the aim point (ORBIT6_RADIUS/ORBIT6_SPEED) while its own
 *     arrows keep spinning around that moving local center — a small satellite circle in motion.
 *
 * Arrow orientation: tiny velocity (0.02) in look direction each tick causes
 *   PersistentProjectileEntity to compute the correct visual yaw/pitch from the vector.
 *   noClip + noGravity prevent physics interference while floating.
 *
 * Cost: arrows consumed from inventory first; if none, conjuring is free — a
 * 5% chance per arrow costs 1 hunger point directly (not exhaustion).
 */
public class ArrowManifestManager {

    // Marks every manifested arrow so orphans (left behind by a client crash or a full server
    // restart, both of which wipe `manifested` but not the real spawned entities) can be found
    // and cleaned up later — see sweepOrphans().
    private static final String ORPHAN_TAG        = "solsai_manifest_arrow";
    private static final int    ORPHAN_SWEEP_TICKS = 200; // ~10s — cheap enough to just always run

    private static final double FORWARD_DIST   = 1.8;   // blocks ahead of eye
    private static final double CIRCLE_RADIUS  = 0.55;  // formation radius shared by rings + cross spokes
    private static final int    ARROWS_PER_CIRCLE = 24; // one more circle unlocks every this-many arrows
    private static final double SPOKE_STACK_GAP   = 0.20; // extra radius per arrow stacked beyond 4 on a cross spoke
    private static final double DIVERGE_TILT       = Math.toRadians(30); // plane tilt for diverging round circles (index 3+)
    private static final double GOLDEN_ANGLE       = Math.PI * (3.0 - Math.sqrt(5.0)); // ≈137.5°, even fan-out per circle
    private static final int    ORBIT_CIRCLE_INDEX = 5;   // 0-based — the 6th circle
    private static final double ORBIT6_RADIUS       = 0.9;   // how far its center swings from the aim point
    private static final double ORBIT6_SPEED        = 0.008; // radians/tick — its own, slower revolution
    private static final double ROTATION_SPEED = 0.025; // radians/tick (~one turn per 4s)
    private static final double ARROW_SPEED    = 52.5;  // blocks/tick when released (base 3.5 * 15, per user request)
    private static final double ARROW_DAMAGE   = 2.5;   // hit damage (× 0.5 = hearts)
    private static final double NO_ARROW_HUNGER_CHANCE = 0.05; // chance to cost 1 hunger when no physical arrow is available
    private static final Random RANDOM = new Random();
    private static int tickCount = 0;
    private static int sweepCounter = 0;

    // playerUUID → ordered list of floating arrow UUIDs (order defines circle assignment)
    private static final Map<UUID, List<UUID>> manifested = new ConcurrentHashMap<>();

    private enum Shape { ROUND, PLUS, XCROSS }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Conjure one arrow: consume from inventory or drain exhaustion. */
    public static String manifest(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.computeIfAbsent(player.getUuid(), k -> new ArrayList<>());
            String src = conjureOne(player, list);
            return "{\"ok\":true,\"count\":" + list.size() + ",\"from\":\"" + src + "\"}";
        });
    }

    /** Conjure several arrows in one call — same per-arrow cost/damage rules as manifest(). */
    public static String manifestBurst(MinecraftServer server, String playerName, int count) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.computeIfAbsent(player.getUuid(), k -> new ArrayList<>());
            for (int i = 0; i < count; i++) conjureOne(player, list);
            return "{\"ok\":true,\"count\":" + list.size() + ",\"conjured\":" + count + "}";
        });
    }

    /** Conjures one arrow into `list` and returns its cost source ("inventory" or "hunger"). */
    private static String conjureOne(ServerPlayerEntity player, List<UUID> list) {
        // Consume physical arrow first; fall back to a rare hunger cost
        boolean hadArrow = false;
        var inv = player.getInventory();
        for (int s = 0; s < inv.size(); s++) {
            var stack = inv.getStack(s);
            if (stack.getItem() instanceof ArrowItem) {
                stack.decrement(1);
                hadArrow = true;
                break;
            }
        }
        if (!hadArrow && RANDOM.nextDouble() < NO_ARROW_HUNGER_CHANCE) {
            var hunger = player.getHungerManager();
            hunger.setFoodLevel(Math.max(0, hunger.getFoodLevel() - 1));
        }

        // Compute damage: base + Power V bonus + Strength effect
        Set<Identifier> enchants = EnchantTracker.getActiveSet(player.getUuid());
        double dmg = ARROW_DAMAGE;
        Identifier powerId = new Identifier("minecraft", "power");
        if (enchants.contains(powerId)) {
            Enchantment powerEnch = Registries.ENCHANTMENT.get(powerId);
            if (powerEnch != null) dmg += powerEnch.getMaxLevel() * 0.5 + 0.5;
        }
        if (player.hasStatusEffect(StatusEffects.STRENGTH)) {
            int amp = player.getStatusEffect(StatusEffects.STRENGTH).getAmplifier();
            dmg += 3.0 * (amp + 1);
        }

        // Spawn floating arrow at eye (tick will immediately reposition it)
        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        ArrowEntity arrow = new ArrowEntity(world, player);
        arrow.setPosition(eye.x, eye.y, eye.z);
        arrow.setNoGravity(true);
        // setNoClip(), not the raw noClip field — the field alone never reaches the
        // client (PersistentProjectileEntity.isNoClip() reads the raw field only on
        // the server; on the client it reads the synced PROJECTILE_FLAGS bit that only
        // setNoClip() updates). Left as a raw field write, the client keeps computing
        // this.noClip as false in its own tick() and derives the opposite-signed yaw
        // branch (line ~210 of PersistentProjectileEntity, `bl ? atan2(-e,-g) : atan2(e,g)`)
        // from the same velocity every tick — a permanent 180° client/server disagreement
        // that reads as the arrow spinning/flipping in place instead of holding steady.
        arrow.setNoClip(true);
        // Vanilla's onPlayerCollision() treats noClip as pickup-eligible the same as
        // inGround, so without this a floating arrow sitting at the player's face gets
        // vacuumed into their inventory the instant it spawns. Restored to ALLOWED in
        // shoot() once the arrow is actually released.
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        arrow.setDamage(dmg);
        // Tagged so a crash or full server restart can't orphan it forever — see
        // sweepOrphans() in tick(). manifested (the in-memory tracking map) doesn't survive
        // either of those, but the real ArrowEntity does (it's a normal spawned entity, saved
        // into chunk NBT like anything else), so without this tag there'd be nothing left to
        // ever find and discard it again once tracking is gone.
        arrow.addCommandTag(ORPHAN_TAG);
        world.spawnEntity(arrow);

        list.add(arrow.getUuid());
        return hadArrow ? "inventory" : "hunger";
    }

    /** Release all floating arrows at high speed in the player's look direction.
     *  If none are manifested, conjures and fires one immediately instead. */
    public static String shoot(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.remove(player.getUuid());
            // Nothing manifested — conjure one on the spot so shoot always fires an arrow.
            if (list == null || list.isEmpty()) {
                list = new ArrayList<>();
                conjureOne(player, list);
            }
            ServerWorld world = player.getServerWorld();
            Vec3d look = player.getRotationVector();

            // Read active enchants once for all arrows in this volley
            Set<Identifier> enchants = EnchantTracker.getActiveSet(player.getUuid());
            boolean flame = enchants.contains(new Identifier("minecraft", "flame"));
            int knockback = 0;
            Identifier punchId = new Identifier("minecraft", "punch");
            if (enchants.contains(punchId)) {
                Enchantment ench = Registries.ENCHANTMENT.get(punchId);
                if (ench != null) knockback = ench.getMaxLevel();
            }
            int piercing = 0;
            Identifier piercingId = new Identifier("minecraft", "piercing");
            if (enchants.contains(piercingId)) {
                Enchantment ench = Registries.ENCHANTMENT.get(piercingId);
                if (ench != null) piercing = ench.getMaxLevel();
            }

            int shot = 0;
            for (UUID id : list) {
                if (world.getEntity(id) instanceof ArrowEntity arrow) {
                    arrow.setNoClip(false);
                    arrow.setNoGravity(false);
                    // Infinity-bow style — same PickupPermission vanilla uses for Infinity-enchanted
                    // shots, so these can't be collected back into inventory after landing.
                    arrow.pickupType = PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
                    arrow.setVelocity(look.x * ARROW_SPEED, look.y * ARROW_SPEED, look.z * ARROW_SPEED);
                    if (flame)        arrow.setOnFireFor(100);
                    if (knockback > 0) arrow.setPunch(knockback);
                    if (piercing  > 0) arrow.setPierceLevel((byte) piercing);
                    shot++;
                }
            }
            return "{\"ok\":true,\"shot\":" + shot + "}";
        });
    }

    /** Discard all manifested arrows without shooting. */
    public static String dismiss(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.remove(player.getUuid());
            if (list == null) return "{\"ok\":true}";
            ServerWorld world = player.getServerWorld();
            for (UUID id : list) {
                var e = world.getEntity(id);
                if (e != null) e.discard();
            }
            return "{\"ok\":true}";
        });
    }

    public static String getState(MinecraftServer server, String playerName) {
        var player = server.getPlayerManager().getPlayer(playerName);
        var list   = player != null ? manifested.get(player.getUuid()) : null;
        return "{\"count\":" + (list == null ? 0 : list.size()) + "}";
    }

    public static void clearPlayer(UUID uuid) { manifested.remove(uuid); }

    public static void onServerStart() { manifested.clear(); tickCount = 0; sweepCounter = 0; }

    // ── Tick — reposition each arrow in its formation slot ─────────────────────

    public static void tick(MinecraftServer server) {
        // Runs unconditionally, regardless of whether `manifested` currently has anything in
        // it — that's exactly the state right after a restart, before anyone manifests a new
        // arrow, which is when orphan cleanup matters most.
        if (++sweepCounter >= ORPHAN_SWEEP_TICKS) {
            sweepCounter = 0;
            sweepOrphans(server);
        }

        if (manifested.isEmpty()) return;
        double t = ++tickCount * ROTATION_SPEED;

        var iter = manifested.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) { discardAll(server, entry.getValue()); iter.remove(); continue; }

            ServerWorld world = player.getServerWorld();
            List<UUID> list = entry.getValue();

            // Prune arrows that were picked up or naturally despawned
            list.removeIf(id -> { var e = world.getEntity(id); return e == null || e.isRemoved(); });
            if (list.isEmpty()) { iter.remove(); continue; }

            Vec3d eye  = player.getEyePos();
            Vec3d look = player.getRotationVector(); // normalised look direction

            // Formation plane basis, perpendicular to the CURRENT look direction (pitch included) —
            // makes circle 0 face the camera like a target reticle at any pitch. Built from the
            // standard cross product, but swapping the reference axis to world-FORWARD right at the
            // pole (where look·worldUp is ~1) — world-up itself is never a safe reference there —
            // keeps the basis well-defined even looking straight up or down.
            Vec3d worldUp = new Vec3d(0, 1, 0);
            Vec3d refUp   = Math.abs(look.y) > 0.999 ? new Vec3d(0, 0, 1) : worldUp;
            Vec3d right   = look.crossProduct(refUp).normalize();
            Vec3d ringUp  = right.crossProduct(look).normalize();
            Vec3d center  = eye.add(look.multiply(FORWARD_DIST));

            int n = list.size();
            int totalCircles = circleCountFor(n);

            for (int i = 0; i < n; i++) {
                if (!(world.getEntity(list.get(i)) instanceof ArrowEntity arrow)) continue;

                // Round-robin circle assignment so each circle stays evenly populated
                // regardless of exactly how n divides.
                int circle, idxInCircle, circleSize;
                if (n == 1) {
                    circle = 0; idxInCircle = 0; circleSize = 1;
                } else {
                    circle      = i % totalCircles;
                    idxInCircle = i / totalCircles;
                    circleSize  = n / totalCircles + (circle < n % totalCircles ? 1 : 0);
                }

                Vec3d pos = positionInFormation(circle, idxInCircle, circleSize, center, look, right, ringUp, t);
                arrow.setPosition(pos.x, pos.y, pos.z);
                // Tiny velocity in look direction — causes the renderer to orient the
                // arrow model toward the crosshair (PersistentProjectileEntity derives
                // visual yaw/pitch from velocity direction each tick).
                // Horizontal (x/z) components are fed in NEGATED on purpose:
                // PersistentProjectileEntity.tick() computes yaw as atan2(-vx,-vz) whenever
                // noClip is true (vanilla's case for that branch is an arrow bounced backward
                // off a shield, kept looking forward-facing) vs. the normal atan2(vx,vz) when
                // noClip is false. Our floating arrows need noClip=true (so they don't collide/
                // fall while parked in front of the player — see manifest()'s setNoClip(true)),
                // which put them on the flipped branch and pointed them backward at the player
                // on the horizontal axis. Pre-negating x/z here cancels that flip exactly
                // (atan2(-(-vx),-(-vz)) == atan2(vx,vz)), so the rendered yaw ends up correct
                // without having to touch noClip. Y is left alone because pitch
                // (atan2(vy, horizontalLength)) never branches on noClip in the first place —
                // vertical facing was already correct.
                arrow.setVelocity(-look.x * 0.02, look.y * 0.02, -look.z * 0.02);
                // EntityType.ARROW has a 20-tick tracking interval (syncs to clients once
                // a second by default) and setVelocity() alone doesn't mark the entity
                // dirty, so without this the formation motion above was only reaching clients
                // once/sec — visible as a stutter: reposition, then re-spin to the new
                // look direction, once a second. velocityDirty forces EntityTrackerEntry
                // to send a fresh position+rotation packet every tick instead.
                arrow.velocityDirty = true;
                arrow.setNoGravity(true);
                arrow.setNoClip(true);
            }
        }
    }

    // ── Formation math ──────────────────────────────────────────────────────────

    /** One more circle unlocks every ARROWS_PER_CIRCLE arrows — 1-24 arrows is a single
     *  circle, 25-48 adds a second, and so on, uncapped. */
    private static int circleCountFor(int arrowCount) {
        return Math.max(1, (int) Math.ceil(arrowCount / (double) ARROWS_PER_CIRCLE));
    }

    /** Circle 0 is always round. Circles 1 and 2 (once unlocked) are the "+" and "X" crosses,
     *  layered with the round circles rather than replacing them. Circle 3 and up are round
     *  again (diverging — see positionInFormation). */
    private static Shape shapeOf(int circle) {
        if (circle == 1) return Shape.PLUS;
        if (circle == 2) return Shape.XCROSS;
        return Shape.ROUND;
    }

    private static Vec3d positionInFormation(int circle, int idxInCircle, int circleSize,
                                              Vec3d center, Vec3d look, Vec3d right, Vec3d ringUp, double t) {
        Shape shape = shapeOf(circle);

        // Spin direction: the "+" and "X" crosses always spin opposite each other regardless of
        // index; round circles alternate by index so neighbouring rings read as layered rather
        // than moving in lockstep.
        double dir = shape == Shape.PLUS ? 1.0 : shape == Shape.XCROSS ? -1.0 : (circle % 2 == 0 ? 1.0 : -1.0);

        double angle, radius;
        if (shape != Shape.ROUND) {
            // 4 spokes through the center (X is the same spokes rotated 45°). Beyond 4 arrows
            // in this circle, extra arrows stack further out along the same 4 spokes instead of
            // bunching up — still reads as a cross no matter how many arrows land here.
            double baseOffset = shape == Shape.XCROSS ? Math.PI / 4.0 : 0.0;
            int spoke = idxInCircle % 4;
            int stack = idxInCircle / 4;
            angle  = baseOffset + spoke * (Math.PI / 2.0) + t * dir;
            radius = CIRCLE_RADIUS + stack * SPOKE_STACK_GAP;
        } else {
            angle  = (circleSize <= 1 ? 0.0 : 2 * Math.PI * idxInCircle / circleSize) + t * dir;
            radius = CIRCLE_RADIUS;
        }

        Vec3d planeRight = right, planeUp = ringUp, formationCenter = center;

        // Diverging round circles (index 3+, skipping the orbiting 6th): tilt this circle's
        // plane away from straight-ahead by DIVERGE_TILT, fanned around the look axis at the
        // golden angle per extra circle so they spread out evenly instead of stacking the same
        // way — overlapping tilted rings around one shared center, like the reference image.
        if (shape == Shape.ROUND && circle >= 3 && circle != ORBIT_CIRCLE_INDEX) {
            double azimuth = (circle - 3) * GOLDEN_ANGLE;
            Vec3d tiltAxis = right.multiply(Math.cos(azimuth)).add(ringUp.multiply(Math.sin(azimuth))).normalize();
            planeRight = rotateAroundAxis(right, tiltAxis, DIVERGE_TILT);
            planeUp    = rotateAroundAxis(ringUp, tiltAxis, DIVERGE_TILT);
        }

        // The 6th circle (index 5) doesn't sit still at the aim point like the others — its
        // whole formation orbits the aim point on its own, slower revolution while its arrows
        // keep spinning around that moving local center; shrunk a little so it reads as a
        // satellite circle in motion rather than just another static ring.
        if (circle == ORBIT_CIRCLE_INDEX) {
            double orbitAngle = t * (ORBIT6_SPEED / ROTATION_SPEED);
            double cosO = Math.cos(orbitAngle) * ORBIT6_RADIUS, sinO = Math.sin(orbitAngle) * ORBIT6_RADIUS;
            formationCenter = center.add(
                right.x * cosO + ringUp.x * sinO,
                right.y * cosO + ringUp.y * sinO,
                right.z * cosO + ringUp.z * sinO
            );
            radius *= 0.6;
        }

        double cosA = Math.cos(angle) * radius, sinA = Math.sin(angle) * radius;
        return formationCenter.add(
            planeRight.x * cosA + planeUp.x * sinA,
            planeRight.y * cosA + planeUp.y * sinA,
            planeRight.z * cosA + planeUp.z * sinA
        );
    }

    /** Rodrigues' rotation formula — rotates `v` by `angle` radians around unit vector `axis`. */
    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.multiply(cos)
            .add(axis.crossProduct(v).multiply(sin))
            .add(axis.multiply(axis.dotProduct(v) * (1 - cos)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private static void discardAll(MinecraftServer server, List<UUID> ids) {
        for (UUID id : ids) {
            for (ServerWorld w : server.getWorlds()) {
                var e = w.getEntity(id);
                if (e != null) { e.discard(); break; }
            }
        }
    }

    /** Finds any ORPHAN_TAG-tagged arrow not currently tracked by ANY player's manifested
     *  list — left behind by a client crash or a full server restart, both of which wipe the
     *  in-memory `manifested` map but not the real spawned entity — and discards it. Self-heals
     *  as soon as an orphan's chunk becomes loaded again rather than only checking once at
     *  server start (chunks containing it aren't even loaded that early). */
    private static void sweepOrphans(MinecraftServer server) {
        Set<UUID> tracked = new HashSet<>();
        for (List<UUID> list : manifested.values()) tracked.addAll(list);

        for (ServerWorld world : server.getWorlds()) {
            var orphans = world.getEntitiesByType(TypeFilter.instanceOf(ArrowEntity.class),
                arrow -> arrow.getCommandTags().contains(ORPHAN_TAG) && !tracked.contains(arrow.getUuid()));
            for (ArrowEntity orphan : orphans) orphan.discard();
        }
    }

    @FunctionalInterface interface PlayerAction { String run(ServerPlayerEntity p); }

    private static String dispatch(MinecraftServer server, String playerName, PlayerAction action) {
        CompletableFuture<String> f = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(playerName);
                if (p == null) { f.complete("{\"error\":\"player not found\"}"); return; }
                f.complete(action.run(p));
            } catch (Exception e) {
                f.complete("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
            }
        });
        try { return f.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }
}
