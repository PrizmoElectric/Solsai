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
 *   - Arrows are dealt across `totalCircles` formations, one more circle unlocking every
 *     ARROWS_PER_CIRCLE arrows (see circleCountFor()), capped at MAX_CIRCLES — and MAX_ARROWS
 *     caps the total so entity count (and client FPS) stay bounded regardless of how much a
 *     player conjures.
 *   - Each circle has its own radius (RADIUS_BASE + circle*RADIUS_STEP — bigger circles further
 *     out in the unlock order) and, from circle 1 on, its own center: shifted off the aim point
 *     fanned around the look axis at the golden angle, scaled to that circle's own radius — so
 *     circles read as differently sized and differently positioned instead of stacking on
 *     exactly the same point. Arrows are split across circles by distributeArrows(), weighted by
 *     each circle's radius so density (spacing between arrows) stays roughly even everywhere
 *     instead of just splitting the count evenly.
 *   - Circle 0 is a plain round ring facing the player like a target reticle (no shift/tilt).
 *   - Circle 1 is a "+" cross — arrows sit on 4 spokes through the center — and circle 2 is an
 *     "X" cross, spokes rotated 45°. The two crosses spin in OPPOSITE directions from each
 *     other, layered with the round circles rather than replacing them.
 *   - Circle 3 and up (round again) additionally DIVERGE: tilted DIVERGE_TILT off straight-ahead
 *     around the same golden-angle azimuth as their position shift — overlapping tilted rings
 *     around one shared area, like a flower/mandala, rather than flat concentric circles.
 *   - The circle at ORBIT_CIRCLE_INDEX (the 6th) is special: instead of sitting at a fixed offset
 *     like the others, its whole formation orbits the aim point (ORBIT6_RADIUS/ORBIT6_SPEED)
 *     while its own arrows keep spinning around that moving local center.
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
    private static final double RADIUS_BASE    = 0.42;  // circle 0's formation radius
    private static final double RADIUS_STEP    = 0.10;  // radius growth per additional circle
    private static final double POSITION_OFFSET_FRACTION = 0.4; // per-circle center shift, relative to its own radius
    private static final int    ARROWS_PER_CIRCLE = 12; // one more circle unlocks every this-many arrows
    private static final int    MAX_CIRCLES       = 10; // hard cap on circle count
    private static final int    MAX_ARROWS        = MAX_CIRCLES * ARROWS_PER_CIRCLE; // hard cap on total manifested arrows — keeps entity count (and FPS) bounded
    private static final double SPOKE_STACK_GAP   = 0.20; // extra radius per arrow stacked beyond 4 on a cross spoke
    private static final double DIVERGE_TILT       = Math.toRadians(35); // plane tilt for diverging round circles (index 3+)
    private static final double GOLDEN_ANGLE       = Math.PI * (3.0 - Math.sqrt(5.0)); // ≈137.5°, even fan-out per circle
    private static final int    ORBIT_CIRCLE_INDEX = 5;   // 0-based — the 6th circle
    private static final double ORBIT6_RADIUS       = 0.9;   // how far its center swings from the aim point
    private static final double ORBIT6_SPEED        = 0.008; // radians/tick — its own, slower revolution
    private static final double ROTATION_SPEED = 0.025; // radians/tick (~one turn per 4s)
    private static final double ARROW_SPEED    = 52.5;  // blocks/tick when released (base 3.5 * 15, per user request)
    private static final double ARROW_DAMAGE   = 2.5;   // hit damage (× 0.5 = hearts)
    private static final double NO_ARROW_HUNGER_CHANCE = 0.05; // chance to cost 1 hunger when no physical arrow is available
    private static final double HUNGER_SPEED_MULTIPLIER       = 0.2; // 80% speed reduction when hunger is empty
    private static final int    HUNGER_MANIFEST_COOLDOWN_TICKS = 20; // 1s between conjures while hunger is empty
    private static final Random RANDOM = new Random();
    private static int tickCount = 0;
    private static int sweepCounter = 0;

    // playerUUID → ordered list of floating arrow UUIDs (order defines circle assignment)
    private static final Map<UUID, List<UUID>> manifested = new ConcurrentHashMap<>();

    // playerUUID → server tick of their last conjure while hunger was empty — throttles
    // manifest()/manifestBurst() to one arrow/second under HUNGER_MANIFEST_COOLDOWN_TICKS
    // instead of the usual instant conjure, once the player's stomach is actually empty.
    private static final Map<UUID, Integer> lastHungryManifestTick = new ConcurrentHashMap<>();

    private enum Shape { ROUND, PLUS, XCROSS }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Conjure one arrow: consume from inventory or drain exhaustion. No-op once MAX_ARROWS
     *  is already manifested — see the class doc for why the total is capped. Also no-op if
     *  the player's hunger is empty and they're still within HUNGER_MANIFEST_COOLDOWN_TICKS of
     *  their last conjure — see checkHungryCooldown(). */
    public static String manifest(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.computeIfAbsent(player.getUuid(), k -> new ArrayList<>());
            if (list.size() >= MAX_ARROWS) {
                return "{\"ok\":true,\"count\":" + list.size() + ",\"maxed\":true}";
            }
            if (!checkHungryCooldown(player)) {
                return "{\"ok\":true,\"count\":" + list.size() + ",\"cooldown\":true}";
            }
            String src = conjureOne(player, list);
            return "{\"ok\":true,\"count\":" + list.size() + ",\"from\":\"" + src + "\"}";
        });
    }

    /** Conjure several arrows in one call — same per-arrow cost/damage rules as manifest(),
     *  same MAX_ARROWS cap and hunger cooldown (both stop the loop early rather than erroring —
     *  a burst call while hunger is empty effectively yields just one arrow, same as manifest()
     *  would, one per second). */
    public static String manifestBurst(MinecraftServer server, String playerName, int count) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.computeIfAbsent(player.getUuid(), k -> new ArrayList<>());
            int conjured = 0;
            while (conjured < count && list.size() < MAX_ARROWS && checkHungryCooldown(player)) {
                conjureOne(player, list);
                conjured++;
            }
            return "{\"ok\":true,\"count\":" + list.size() + ",\"conjured\":" + conjured + "}";
        });
    }

    /** Returns false (and refuses to conjure) if the player's hunger is empty and less than
     *  HUNGER_MANIFEST_COOLDOWN_TICKS have passed since their last conjure made while hungry;
     *  otherwise true, recording this tick as the new "last hungry conjure" when hunger is
     *  empty. Players with any food are never throttled. */
    private static boolean checkHungryCooldown(ServerPlayerEntity player) {
        if (player.getHungerManager().getFoodLevel() > 0) return true;
        Integer last = lastHungryManifestTick.get(player.getUuid());
        if (last != null && tickCount - last < HUNGER_MANIFEST_COOLDOWN_TICKS) return false;
        lastHungryManifestTick.put(player.getUuid(), tickCount);
        return true;
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

            // Hunger's already empty at shoot time (not just at manifest time) costs 80% of
            // the volley's speed — a starving player's arrows still launch, just weakly.
            double speed = player.getHungerManager().getFoodLevel() > 0
                ? ARROW_SPEED : ARROW_SPEED * HUNGER_SPEED_MULTIPLIER;

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
                    arrow.setVelocity(look.x * speed, look.y * speed, look.z * speed);
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

    public static void clearPlayer(UUID uuid) { manifested.remove(uuid); lastHungryManifestTick.remove(uuid); }

    public static void onServerStart() {
        manifested.clear();
        lastHungryManifestTick.clear();
        tickCount = 0;
        sweepCounter = 0;
    }

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

            double[] radii = new double[totalCircles];
            for (int c = 0; c < totalCircles; c++) radii[c] = circleRadius(c);
            int[] counts = distributeArrows(n, radii);
            int[] boundary = new int[totalCircles + 1];
            for (int c = 0; c < totalCircles; c++) boundary[c + 1] = boundary[c] + counts[c];

            for (int i = 0; i < n; i++) {
                if (!(world.getEntity(list.get(i)) instanceof ArrowEntity arrow)) continue;

                int circle = 0;
                while (circle < totalCircles - 1 && i >= boundary[circle + 1]) circle++;
                int idxInCircle = i - boundary[circle];
                int circleSize  = counts[circle];

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

    /** One more circle unlocks every ARROWS_PER_CIRCLE arrows — 1-12 arrows is a single
     *  circle, 13-24 adds a second, and so on, up to MAX_CIRCLES. */
    private static int circleCountFor(int arrowCount) {
        return Math.min(MAX_CIRCLES, Math.max(1, (int) Math.ceil(arrowCount / (double) ARROWS_PER_CIRCLE)));
    }

    /** Each circle's own formation radius — grows with index so later circles read as
     *  distinctly bigger, not just further off to the side. */
    private static double circleRadius(int circle) {
        return RADIUS_BASE + circle * RADIUS_STEP;
    }

    /** Splits n arrows across the given circles, weighted by each circle's radius (bigger
     *  circles have more circumference to fill, so they get proportionally more arrows) —
     *  keeps arrow density roughly even across differently-sized circles instead of just
     *  dividing the count evenly. Uses the largest-remainder method so the counts always sum
     *  to exactly n despite the flooring. */
    private static int[] distributeArrows(int n, double[] radii) {
        int c = radii.length;
        int[] counts = new int[c];
        if (c == 1) { counts[0] = n; return counts; }

        double totalWeight = 0;
        for (double r : radii) totalWeight += r;

        double[] exact = new double[c];
        int assigned = 0;
        for (int i = 0; i < c; i++) {
            exact[i] = n * radii[i] / totalWeight;
            counts[i] = (int) exact[i];
            assigned += counts[i];
        }

        int remaining = n - assigned;
        Integer[] order = new Integer[c];
        for (int i = 0; i < c; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> Double.compare(exact[b] - counts[b], exact[a] - counts[a]));
        for (int k = 0; k < remaining; k++) counts[order[k]]++;

        return counts;
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
        // index; round circles alternate by index so neighbouring circles read as layered rather
        // than moving in lockstep.
        double dir = shape == Shape.PLUS ? 1.0 : shape == Shape.XCROSS ? -1.0 : (circle % 2 == 0 ? 1.0 : -1.0);

        double baseRadius = circleRadius(circle);
        double angle, radius;
        if (shape != Shape.ROUND) {
            // 4 spokes through the center (X is the same spokes rotated 45°). Beyond 4 arrows
            // in this circle, extra arrows stack further out along the same 4 spokes instead of
            // bunching up — still reads as a cross no matter how many arrows land here.
            double baseOffset = shape == Shape.XCROSS ? Math.PI / 4.0 : 0.0;
            int spoke = idxInCircle % 4;
            int stack = idxInCircle / 4;
            angle  = baseOffset + spoke * (Math.PI / 2.0) + t * dir;
            radius = baseRadius + stack * SPOKE_STACK_GAP;
        } else {
            angle  = (circleSize <= 1 ? 0.0 : 2 * Math.PI * idxInCircle / circleSize) + t * dir;
            radius = baseRadius;
        }

        // Every circle beyond the first shifts its own center away from the aim point — fanned
        // around the look axis at the golden angle per circle, magnitude scaled to that circle's
        // own radius — so circles land at genuinely different positions (not just different
        // shapes stacked on the same point, which read as "too close together").
        double azimuth = circle * GOLDEN_ANGLE;
        Vec3d azDir = right.multiply(Math.cos(azimuth)).add(ringUp.multiply(Math.sin(azimuth)));
        Vec3d formationCenter = circle == 0 ? center : center.add(azDir.multiply(baseRadius * POSITION_OFFSET_FRACTION));

        Vec3d planeRight = right, planeUp = ringUp;

        // Diverging round circles (index 3+, skipping the orbiting one): additionally tilt this
        // circle's plane, using the same azimuth as its position shift so both effects fan out
        // together — overlapping tilted circles around one shared area, like the reference
        // image, rather than flat concentric rings.
        if (shape == Shape.ROUND && circle >= 3 && circle != ORBIT_CIRCLE_INDEX) {
            Vec3d tiltAxis = azDir.normalize();
            planeRight = rotateAroundAxis(right, tiltAxis, DIVERGE_TILT);
            planeUp    = rotateAroundAxis(ringUp, tiltAxis, DIVERGE_TILT);
        }

        // The orbiting circle ignores the static azimuth shift above — its whole formation
        // instead orbits the aim point continuously while its arrows keep spinning around that
        // moving local center; shrunk a little so it reads as a satellite circle in motion.
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
