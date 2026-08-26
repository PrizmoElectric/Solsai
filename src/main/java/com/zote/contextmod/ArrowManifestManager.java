package com.zote.contextmod;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.entity.projectile.PersistentProjectileEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Arrow Manifestation — arrows float around the player in a selectable FORMATION and release
 * according to a selectable FIRE MODE. The two are fully independent axes (any formation
 * combines with any fire mode) except CIRCLES, where every fire mode runs PER-CIRCLE instead
 * of pooled — each circle is its own independent turret with its own arrow supply.
 *
 * ── FORMATIONS ─────────────────────────────────────────────────────────────────
 *   MAGE_CIRCLE (default, today's original formation) — one shared arrow group in the
 *     elaborate front-of-player round/plus/x-cross/orbit formation (see positionInFormation()).
 *     Conjured manually via manifest()/manifestBurst(), same as always.
 *   BARRAGE — one shared arrow group, continuously auto-conjured at random positions behind
 *     the player (own per-player timer in tick()), each holding a fixed random offset that
 *     rides along as the player turns.
 *   CIRCLES (1-4) — N independent arrow groups ("turrets"), each fixed at one of 4 quadrant
 *     slots ahead of the player (top-right/top-left/bottom-right/bottom-left), each with its
 *     OWN cap (CIRCLE_MAX_ARROWS, not shared) and continuously auto-conjured, round-robin
 *     across active circles.
 *
 * ── FIRE MODES ─────────────────────────────────────────────────────────────────
 *   SINGLE — /arrow-shoot releases just the single oldest arrow per relevant group, once.
 *   SHOTGUN (default, today's original behavior) — /arrow-shoot releases every relevant
 *     arrow immediately, in the look direction.
 *   BURST — /arrow-shoot arms a 3-arrow release per relevant group, drained by tick() a couple
 *     ticks apart (BURST_INTERVAL_TICKS), then stops on its own.
 *   MACHINE_GUN — /arrow-shoot arms continuous release per relevant group: tick() releases
 *     one arrow every MACHINE_GUN_INTERVAL_TICKS for as long as the group keeps having arrows
 *     and the mode stays selected — deliberately does NOT auto-stop when a group empties, so
 *     it resumes firing on its own as BARRAGE/CIRCLES keep feeding it (the continuous
 *     "arrow barrage" feel). Switching fire mode or dismissing stops it.
 *   For CIRCLES formation, every mode above runs independently per circle group instead of
 *     pooled across one shared list — see armFireMode()/tick().
 *
 * Arrow orientation, noClip/pickup handling, orphan sweep, and the MAGE_CIRCLE positioning
 * math below are unchanged from the original single-formation version.
 *
 * Cost: arrows consumed from inventory first; if none, conjuring is free — a 5% chance per
 * arrow costs 1 hunger point directly (not exhaustion). That specific hunger charge (not just
 * "no physical arrow available") is tracked per-arrow so dismiss() can refund exactly the
 * arrows that were both hunger-funded AND never fired — see hungerFundedArrows.
 */
public class ArrowManifestManager {

    // Marks every manifested arrow so orphans (left behind by a client crash or a full server
    // restart, both of which wipe in-memory tracking but not the real spawned entities) can be
    // found and cleaned up later — see sweepOrphans().
    private static final String ORPHAN_TAG        = "solsai_manifest_arrow";
    private static final int    ORPHAN_SWEEP_TICKS = 200; // ~10s — cheap enough to just always run

    private static final double FORWARD_DIST   = 1.8;   // MAGE_CIRCLE: blocks ahead of eye
    private static final double RADIUS_BASE    = 0.42;  // MAGE_CIRCLE circle 0's formation radius
    private static final double RADIUS_STEP    = 0.28;  // MAGE_CIRCLE radius growth per additional circle — was 0.10, too tight to tell circles apart (user feedback)
    private static final double POSITION_OFFSET_FRACTION = 0.4; // per-circle center shift, relative to its own radius
    private static final int    ARROWS_PER_CIRCLE = 12; // one more circle unlocks every this-many arrows
    private static final int    MAX_CIRCLES       = 10; // hard cap on MAGE_CIRCLE circle count
    private static final int    MAX_ARROWS        = MAX_CIRCLES * ARROWS_PER_CIRCLE; // MAGE_CIRCLE/BARRAGE shared-group cap
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

    // ── New tuning: formations/fire modes ───────────────────────────────────────
    private static final int    CIRCLE_MAX_ARROWS          = 30;   // per-circle cap, independent of MAX_ARROWS
    private static final int    BARRAGE_SPAWN_INTERVAL_TICKS = 4;  // ~5/sec auto-conjure while BARRAGE/CIRCLES active
    private static final int    MACHINE_GUN_INTERVAL_TICKS   = 4;  // ~5/sec release while a group is machine-gunning
    private static final int    BURST_COUNT                  = 3;
    private static final int    BURST_INTERVAL_TICKS         = 2;  // ~0.1s between the 3 burst shots
    // CIRCLES turret slots: fixed offsets from the aim point, spaced apart so circles never
    // touch. CENTER/LEFT/RIGHT have no vertical shift (fixes 1/2/3-circle layouts sitting too
    // high) — only the 4-circle default layout uses the original 4 corners.
    private static final double TURRET_FORWARD_DIST = 2.2, TURRET_SIDE = 1.2, TURRET_VERT = 0.9;
    private static final double TURRET_RADIUS        = 0.30;
    private enum Slot { CENTER, LEFT, RIGHT, TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    // ── Back ring — circles 5-7 (count > 4). Front 4 (Slot-based, above) are completely
    // unchanged; these are positioned behind the player instead, yaw-relative like BARRAGE
    // (NOT pitch-tied like the front slots' look-based frame — so looking up/down doesn't swing
    // them around). Ring radius expands with how many are on it; the whole ring also slowly
    // orbits, opposite direction and different speed from each turret's own internal arrow-spin
    // (ROTATION_SPEED), so the two motions read as independent layers. ─────────────────────
    private static final double BACK_RING_DISTANCE    = 2.5;   // blocks behind the player
    private static final double BACK_RING_BASE_RADIUS = 1.2;   // ring radius with 1 back turret
    private static final double BACK_RING_RADIUS_STEP = 0.7;   // extra radius per additional back turret
    private static final double BACK_RING_ORBIT_SPEED = -0.012; // radians/tick — opposite sign + different magnitude than ROTATION_SPEED (0.025)

    // Turret aiming — MAGE_CIRCLE/CIRCLES formations ease their base look-direction/yaw toward
    // the player's live aim instead of snapping to it every tick ("rotational inertia", per the
    // user's own description). This is separate from and layered under ROTATION_SPEED/
    // BACK_RING_ORBIT_SPEED above, which only spin arrows AROUND that (now-eased) center — the
    // orbital spin itself is untouched by this. BARRAGE is intentionally excluded (it positions
    // arrows behind the player for a different purpose, not "aiming").
    private static final float TURRET_TURN_RATE_DEG = 8.0f; // max degrees/tick the eased angle may close per tick (~160°/sec)

    /** Default slot layout per circle count — CENTER for 1, LEFT/RIGHT (vertically centered)
     *  for 2, LEFT/CENTER/RIGHT for 3 (one circle dead-centered on the aim, per the user's
     *  ask), and the original 4-corner layout for 4 (never flagged as wrong). Overridable
     *  per-index via PlayerState.slotOverride / setCircleSlot(). */
    private static Slot[] defaultSlotsFor(int n) {
        return switch (n) {
            case 1 -> new Slot[]{ Slot.CENTER };
            case 2 -> new Slot[]{ Slot.LEFT, Slot.RIGHT };
            case 3 -> new Slot[]{ Slot.LEFT, Slot.CENTER, Slot.RIGHT };
            default -> new Slot[]{ Slot.TOP_RIGHT, Slot.TOP_LEFT, Slot.BOTTOM_RIGHT, Slot.BOTTOM_LEFT };
        };
    }

    /** {sideSign, vertSign} for a turret slot — 0 means no offset on that axis. */
    private static double[] slotSigns(Slot slot) {
        return switch (slot) {
            case CENTER       -> new double[]{ 0,  0 };
            case LEFT         -> new double[]{-1,  0 };
            case RIGHT        -> new double[]{ 1,  0 };
            case TOP_RIGHT    -> new double[]{ 1,  1 };
            case TOP_LEFT     -> new double[]{-1,  1 };
            case BOTTOM_RIGHT -> new double[]{ 1, -1 };
            case BOTTOM_LEFT  -> new double[]{-1, -1 };
        };
    }

    /** Effective slot layout for n circles: the per-index override where set, else the default
     *  for that count. */
    private static Slot[] resolveSlots(PlayerState st, int n) {
        Slot[] defaults = defaultSlotsFor(n);
        Slot[] result = new Slot[n];
        for (int i = 0; i < n; i++) result[i] = st.slotOverride[i] != null ? st.slotOverride[i] : defaults[i];
        return result;
    }

    /** Barrage spawn spacing presets — replaces the old fixed BARRAGE_BEHIND_MIN/MAX/SPREAD_*
     *  constants so spacing is a per-player, RED-TERMINAL-adjustable choice (user: "too close
     *  together, that should be configurable"). NORMAL's numbers are also simply wider than the
     *  original defaults were. */
    private enum SpreadPreset {
        CLOSE(1.2, 2.0, 0.8, 0.6), NORMAL(2.0, 4.0, 1.6, 1.2), WIDE(3.0, 6.0, 2.4, 1.8),
        EXTREME(4.0, 8.0, 3.2, 2.4); // "even wider barrage" (user)
        final double behindMin, behindMax, spreadH, spreadV;
        SpreadPreset(double behindMin, double behindMax, double spreadH, double spreadV) {
            this.behindMin = behindMin; this.behindMax = behindMax;
            this.spreadH = spreadH; this.spreadV = spreadV;
        }
    }

    private static final Random RANDOM = new Random();
    private static int tickCount = 0;
    private static int sweepCounter = 0;

    public enum Formation { MAGE_CIRCLE, BARRAGE, CIRCLES, RAIN }
    public enum FireMode  { SHOTGUN, BURST, MACHINE_GUN, SINGLE }

    // ── RAIN (a one-shot skill, not a persistent formation like the other three) ──────────
    // Marks a real, gravity-affected projectile spawned by castRain() — distinct from
    // ORPHAN_TAG's floating manifested arrows — both for the short-lived discard sweep (§2 of
    // the design) and for RainOwnerImmunityMixin to recognize which arrows need the
    // owner-immunity override (see that mixin: vanilla's own owner-exclusion expires once an
    // arrow is judged to have "left" its shooter, which happens almost immediately here since
    // these spawn RAIN_SPAWN_HEIGHT above the caster — confirmed via javap against the actual
    // mapped ProjectileEntity class, not assumed).
    public static final String RAIN_ARROW_TAG       = "solsai_rain_arrow"; // public: read cross-package by RainArrowOwnerImmunityMixin
    private static final int    RAIN_COOLDOWN_TICKS      = 200;  // 10s between casts, confirmed
    private static final int    RAIN_SPAWN_INTERVAL_TICKS = 2;   // stagger: one arrow every 2 ticks
    private static final int    RAIN_ARROW_LIFETIME_TICKS = 60;  // 3s — force-discard regardless of landing, keeps concurrent count low
    private static final double RAIN_RADIUS        = 5.0;   // blocks — horizontal scatter around the target center
    private static final double RAIN_SPAWN_HEIGHT  = 15.0;  // blocks above the caster's eyes
    private static final double RAIN_AIM_RANGE     = 15.0;  // blocks — flat eye+look projection for "aimed" target mode
    private static final double RAIN_FALL_SPEED    = 1.2;   // blocks/tick downward at spawn (gravity does the rest)
    private static final double RAIN_SCATTER_ANGLE = 0.15;  // small random horizontal velocity component — "rain," not dead-vertical needles
    private static final int    RAIN_HUNGER_DIVISOR = 20;   // cost = ceil(rainCount / this) hunger points
    private static final double RAIN_LIFE_HEALTH_FLOOR = 0.5; // life-drain fallback never takes health below this fraction of max

    // ── Decorative rune particles — ParticleTypes.ENCHANT only (verified from this project's
    // own history: session 37 removed BOTH ParticleTypes.ENCHANT and ParticleTypes.END_ROD
    // rings for being "excessive" — user now wants ENCHANT back specifically, END_ROD stays
    // gone). Off by default, two independent toggles, deliberately NOT tied to whether any
    // arrows are currently manifested — a purely ambient/cosmetic ring either way. ─────────
    private static final double RUNE_SPELL_RADIUS   = 1.0;  // around the "spell area" point (eye + look*FORWARD_DIST)
    private static final double RUNE_AURA_RADIUS    = 1.2;  // around the player's own body, chest height
    private static final double RUNE_ROTATION_SPEED = 0.05; // radians/tick — faster than formation spin, needs to read clearly with single-particle-per-tick spawning

    // ── Speed regulator — two independent knobs, both cost-scaled: "the bigger the speed the
    // bigger the chance of removing one hunger bar" (user, verbatim). fireRateMultiplier scales
    // how often Machine Gun releases / Barrage-Circles auto-conjure (and the existing
    // NO_ARROW_HUNGER_CHANCE scales right along with it); flightSpeedMultiplier scales released
    // arrows' actual travel speed and introduces a NEW per-release hunger chance (release never
    // cost anything before this — only conjuring did). ─────────────────────────────────────
    private static final double SPEED_MULTIPLIER_MIN = 0.5, SPEED_MULTIPLIER_MAX = 6.0; // was 3.0 — "MG 5x faster" needs a 5.0 preset to be reachable, not clamped down
    private static final double RELEASE_HUNGER_CHANCE_BASE = 0.03; // scaled by flightSpeedMultiplier at release

    // arrowUUID -> server tick it was spawned, global across players (age-based force-discard
    // doesn't need to know whose it is) — see tickRainArrows().
    private static final Map<UUID, Integer> rainArrowSpawnTick = new ConcurrentHashMap<>();

    /** One arrow group — MAGE_CIRCLE/BARRAGE always have exactly one (slot = null); CIRCLES
     *  has circleCount of these, one per active turret slot. slot is mutable (not final) so
     *  setCircleSlot() can retarget an already-active circle without rebuilding the group. */
    private static class ArrowGroup {
        final List<UUID> arrows = new ArrayList<>();
        Slot slot; // null for MAGE_CIRCLE/BARRAGE's single shared group, AND for back-ring turrets (5th+ circle)
        boolean mgActive = false;         // MACHINE_GUN: keep draining until turned off
        int     mgCooldown = 0;
        int     burstRemaining = 0;       // BURST: arrows still owed from the current pulse
        int     burstCooldown = 0;
        // Back ring (5th-7th circle, when count > 4) — front turrets leave these at -1/0 and use
        // `slot` instead; back turrets leave `slot` null and use these. See groupCenter().
        int backRingIndex = -1;
        int backRingTotal = 0;
        ArrowGroup(Slot slot) { this.slot = slot; }
    }

    private static class PlayerState {
        Formation formation   = Formation.MAGE_CIRCLE;
        FireMode  fireMode    = FireMode.SHOTGUN;
        int       circleCount = 1; // only meaningful when formation == CIRCLES
        List<ArrowGroup> groups = new ArrayList<>(List.of(new ArrowGroup(null)));
        int barrageSpawnCooldown = 0;
        // BARRAGE only: each arrow's fixed random offset (yaw-relative to the player), keyed
        // by arrow UUID so tick() keeps re-applying the same relative spot every frame.
        final Map<UUID, Vec3d> barrageOffsets = new HashMap<>();
        int nextRoundRobinCircle = 0; // CIRCLES: which group gets the next auto-conjured arrow
        // Per-circle-index slot override (index 0-3), null = use defaultSlotsFor()'s default.
        Slot[] slotOverride = new Slot[4];
        SpreadPreset barrageSpread = SpreadPreset.NORMAL;
        // Set by dismiss(), cleared by any explicit "I want more" action (manifest/manifestBurst/
        // setFormation/setFireMode) — see autoConjure(). Fixes: dismiss not actually stopping
        // Barrage/Circles auto-conjure, which just refilled itself on the very next timer tick.
        boolean autoSpawnPaused = false;

        // RAIN — configurable in RED TERMINAL's SKILLS tab, not the Arrow loadout strip (Rain
        // isn't a persistent formation the other controls apply to).
        int     rainCount            = 100;   // 1-500
        boolean rainTargetSelf       = true;  // true=centered on self, false=aimed at crosshair
        boolean rainLifeDrainEnabled = false; // opt-in fallback once hunger is empty
        int     rainCooldownTicks    = 0;     // ticks left before castRain() can fire again — ticks down regardless of current formation
        int     rainSpawnRemaining   = 0;     // arrows still to spawn from the in-progress cast
        int     rainSpawnCooldown    = 0;     // ticks until the next staggered spawn
        Vec3d   rainCenter           = null;  // resolved once per cast, reused by every spawn in it

        // Decorative rune particles — off by default (see RUNE_* constants above).
        boolean runeSpellEnabled = false;
        boolean runeAuraEnabled  = false;
        double  runeAngle        = 0; // shared rotation phase for both rings

        // Speed regulator — both default 1.0 (today's exact existing behavior).
        double fireRateMultiplier    = 1.0; // 0.5-6.0, scales MG/Barrage-Circles cadence + conjure hunger-chance
        double flightSpeedMultiplier = 1.0; // 0.5-6.0, scales released-arrow speed + adds a per-release hunger-chance

        // CIRCLES: simultaneous (default, every circle drains independently in parallel — today's
        // exact behavior) vs sequential (one shared round-robin cooldown/turn cycles through
        // groups, one shot per turn). sequentialTurnIndex/Cooldown only mean anything when true.
        boolean circlesSequential  = false;
        int     sequentialTurnIndex = 0;
        int     sequentialCooldown  = 0;

        // Turret aiming — eased yaw/pitch that MAGE_CIRCLE/CIRCLES formations position off of,
        // instead of the player's live yaw/pitch directly (see TURRET_TURN_RATE_DEG above).
        // Shared by every turret/slot/ring this player has, since they all derive from the same
        // look direction each tick. turretAngleInit lets the very first read snap straight to the
        // player's real facing instead of easing in from the (0,0) default.
        float   turretYaw       = 0f;
        float   turretPitch     = 0f;
        boolean turretAngleInit = false;
    }

    private static final Map<UUID, PlayerState> states = new ConcurrentHashMap<>();

    // playerUUID -> currently-equipped skill name for MasterWheelScreen's "Skill" wedge. Kept
    // generic-shaped (a String, not a Rain-specific flag) so a second skill later is a registry
    // addition, not a rearchitecture — see the plan's §5 note on why this exists at all with
    // only one skill so far.
    private static final Map<UUID, String> equippedSkill = new ConcurrentHashMap<>();

    // playerUUID -> set of arrow UUIDs whose conjure cost was ACTUALLY paid in hunger (not just
    // "no physical arrow" — the 5% chance has to have actually fired). Only these refund on
    // dismiss; arrows that cost nothing (95% of the no-arrow case) never did, so never refund.
    private static final Map<UUID, Set<UUID>> hungerFundedArrows = new ConcurrentHashMap<>();

    // playerUUID -> server tick of their last conjure while hunger was empty — throttles
    // conjuring to one arrow/second under HUNGER_MANIFEST_COOLDOWN_TICKS once hunger is empty.
    private static final Map<UUID, Integer> lastHungryManifestTick = new ConcurrentHashMap<>();

    private enum Shape { ROUND, PLUS, XCROSS }

    private static PlayerState stateFor(UUID uuid) { return states.computeIfAbsent(uuid, k -> new PlayerState()); }

    // ── Public API — conjure ──────────────────────────────────────────────────

    /** Conjure one arrow into the appropriate group for the player's current formation
     *  (MAGE_CIRCLE/BARRAGE's shared group, or CIRCLES' round-robin-selected turret). No-op
     *  once that group's own cap is already reached. Also no-op if hunger is empty and still
     *  within HUNGER_MANIFEST_COOLDOWN_TICKS of the last hungry conjure. */
    public static String manifest(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            if (st.formation == Formation.RAIN) return "{\"ok\":true,\"count\":0,\"rain\":true}";

            // Summon key interrupts an active consecutive-fire sequence instead of conjuring —
            // user's ask. Checked before anything else; if any group is actively draining
            // (Machine Gun or an in-progress Burst pulse), stop them all and stop there.
            boolean wasFiring = false;
            for (ArrowGroup g : st.groups) {
                if (g.mgActive || g.burstRemaining > 0) { g.mgActive = false; g.burstRemaining = 0; wasFiring = true; }
            }
            if (wasFiring) return "{\"ok\":true,\"interrupted\":true}";

            st.autoSpawnPaused = false;
            ArrowGroup group = groupForNewArrow(st);
            int cap = st.formation == Formation.CIRCLES ? CIRCLE_MAX_ARROWS : MAX_ARROWS;
            if (group.arrows.size() >= cap) {
                return "{\"ok\":true,\"count\":" + totalArrows(st) + ",\"maxed\":true}";
            }
            if (!checkHungryCooldown(player)) {
                return "{\"ok\":true,\"count\":" + totalArrows(st) + ",\"cooldown\":true}";
            }
            String src = conjureOne(player, st, group);
            return "{\"ok\":true,\"count\":" + totalArrows(st) + ",\"from\":\"" + src + "\"}";
        });
    }

    /** Conjure several arrows in one call — same per-arrow cost/cap/cooldown rules as
     *  manifest(). */
    public static String manifestBurst(MinecraftServer server, String playerName, int count) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            if (st.formation == Formation.RAIN) return "{\"ok\":true,\"count\":0,\"rain\":true}";
            st.autoSpawnPaused = false;
            int conjured = 0;
            while (conjured < count) {
                ArrowGroup group = groupForNewArrow(st);
                int cap = st.formation == Formation.CIRCLES ? CIRCLE_MAX_ARROWS : MAX_ARROWS;
                if (group.arrows.size() >= cap || !checkHungryCooldown(player)) break;
                conjureOne(player, st, group);
                conjured++;
            }
            return "{\"ok\":true,\"count\":" + totalArrows(st) + ",\"conjured\":" + conjured + "}";
        });
    }

    private static int totalArrows(PlayerState st) {
        int n = 0;
        for (ArrowGroup g : st.groups) n += g.arrows.size();
        return n;
    }

    /** Which group a newly-conjured arrow goes into: the single shared group for
     *  MAGE_CIRCLE/BARRAGE, or the next group in round-robin order for CIRCLES. */
    private static ArrowGroup groupForNewArrow(PlayerState st) {
        if (st.formation != Formation.CIRCLES) return st.groups.get(0);
        ArrowGroup group = st.groups.get(st.nextRoundRobinCircle % st.groups.size());
        st.nextRoundRobinCircle = (st.nextRoundRobinCircle + 1) % st.groups.size();
        return group;
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

    /** Conjures one arrow into `group` and returns its cost source ("inventory" or "hunger").
     *  Only marks the arrow hunger-funded (refundable on dismiss) when the 5% chance actually
     *  charges — NOT merely whenever no physical arrow was available. */
    private static String conjureOne(ServerPlayerEntity player, PlayerState st, ArrowGroup group) {
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
        // BUG FIX (confirmed live — free food via repeated summon/dismiss at low hunger):
        // only mark this arrow hunger-funded if food was actually > 0 and genuinely dropped.
        // The old code called setFoodLevel(max(0, foodLevel-1)) and set chargedHunger=true
        // unconditionally — at foodLevel==0 that's a clamped no-op (nothing really charged),
        // but the arrow was still tracked as refundable, so dismissing it unfired later
        // handed back a real +1 food point that was never actually paid. Gating on
        // foodLevel > 0 up front makes "funded" mean what it says: a real cost was paid.
        boolean chargedHunger = false;
        double conjureChance = Math.min(1.0, NO_ARROW_HUNGER_CHANCE * st.fireRateMultiplier);
        if (!hadArrow && RANDOM.nextDouble() < conjureChance) {
            var hunger = player.getHungerManager();
            if (hunger.getFoodLevel() > 0) {
                hunger.setFoodLevel(hunger.getFoodLevel() - 1);
                chargedHunger = true;
            }
        }

        // Compute damage: base + Power V bonus + Strength effect
        Set<Identifier> enchants = SummonEnchantTracker.getActiveSet(player.getUuid(), "arrow");
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

        ServerWorld world = player.getServerWorld();
        Vec3d eye = player.getEyePos();
        ArrowEntity arrow = new ArrowEntity(world, player);

        // Spawn already at the real intended formation position instead of always at eye —
        // eliminates a visible "spin-up" glitch the user hit in fast auto-fire (Machine Gun +
        // Barrage/Circles): spawning at eye and only reaching the real slot on the NEXT tick
        // left the client's entity-interpolation smoothing still mid-travel when a fast release
        // fired the arrow almost immediately, so it visibly "wasn't in position yet." Mage
        // Circle is untouched (manual conjure only, never flagged as having this problem).
        Vec3d spawnAt = eye;
        Vec3d barrageOffset = null;
        if (st.formation == Formation.BARRAGE) {
            barrageOffset = randomBarrageOffset(st.barrageSpread);
            spawnAt = barrageWorldPos(player, barrageOffset);
        } else if (st.formation == Formation.CIRCLES) {
            ensureTurretAngleInit(player, st);
            spawnAt = groupCenter(player, group, smoothedLookVec(st), st.turretYaw);
        }
        arrow.setPosition(spawnAt.x, spawnAt.y, spawnAt.z);
        arrow.setNoGravity(true);
        // setNoClip(), not the raw noClip field — see original class notes: the field alone
        // never reaches the client, causing a permanent client/server yaw disagreement that
        // reads as spinning/flipping in place if skipped.
        arrow.setNoClip(true);
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.DISALLOWED;
        arrow.setDamage(dmg);
        arrow.addCommandTag(ORPHAN_TAG);
        world.spawnEntity(arrow);

        group.arrows.add(arrow.getUuid());
        if (chargedHunger) {
            hungerFundedArrows.computeIfAbsent(player.getUuid(), k -> ConcurrentHashMap.newKeySet())
                .add(arrow.getUuid());
        }
        if (barrageOffset != null) {
            st.barrageOffsets.put(arrow.getUuid(), barrageOffset);
        }
        return hadArrow ? "inventory" : "hunger";
    }

    private static Vec3d randomBarrageOffset(SpreadPreset preset) {
        double back = preset.behindMin + RANDOM.nextDouble() * (preset.behindMax - preset.behindMin);
        double side = (RANDOM.nextDouble() * 2 - 1) * preset.spreadH;
        double up   = (RANDOM.nextDouble() * 2 - 1) * preset.spreadV;
        return new Vec3d(side, up, -back); // player-local: -Z is behind in the frame built in tick()
    }

    /** Player-local (side, up, -back) offset -> world position — shared by conjureOne()'s
     *  pre-spawn positioning and positionBarrage()'s per-tick repositioning. */
    private static Vec3d barrageWorldPos(ServerPlayerEntity player, Vec3d off) {
        double yaw = Math.toRadians(player.getYaw());
        double fx = -Math.sin(yaw), fz = Math.cos(yaw);
        double rx = Math.cos(yaw),  rz = Math.sin(yaw);
        Vec3d eye = player.getEyePos();
        return new Vec3d(eye.x + rx * off.x + fx * off.z, eye.y + off.y, eye.z + rz * off.x + fz * off.z);
    }

    /** Snaps a player's eased turret angle straight to their real current facing the first time
     *  it's ever read (state default is (0,0), which would otherwise cause a visible sweep-in
     *  from world +Z on a brand-new PlayerState). Idempotent — safe to call more than once. */
    private static void ensureTurretAngleInit(ServerPlayerEntity player, PlayerState st) {
        if (st.turretAngleInit) return;
        st.turretYaw = player.getYaw();
        st.turretPitch = player.getPitch();
        st.turretAngleInit = true;
    }

    /** Advances a player's eased turret yaw/pitch one tick closer to their real live look
     *  direction, capped at TURRET_TURN_RATE_DEG/tick — this is the "rotational inertia" the
     *  user asked for: MAGE_CIRCLE/CIRCLES formations position off of this eased value instead
     *  of the player's raw yaw/pitch, so turning the camera now visibly swings the formation
     *  toward the new facing instead of teleporting it there every tick. Called once per player
     *  per tick from positionGroups(), unconditionally (regardless of active formation), so the
     *  eased angle never goes stale while BARRAGE/RAIN is active and doesn't produce a surprise
     *  catch-up sweep the moment the player switches into a formation that uses it. */
    private static void updateTurretAngle(ServerPlayerEntity player, PlayerState st) {
        if (!st.turretAngleInit) { ensureTurretAngleInit(player, st); return; }
        float yawDelta = MathHelper.wrapDegrees(player.getYaw() - st.turretYaw);
        yawDelta = MathHelper.clamp(yawDelta, -TURRET_TURN_RATE_DEG, TURRET_TURN_RATE_DEG);
        st.turretYaw = MathHelper.wrapDegrees(st.turretYaw + yawDelta);

        float pitchDelta = MathHelper.clamp(player.getPitch() - st.turretPitch, -TURRET_TURN_RATE_DEG, TURRET_TURN_RATE_DEG);
        st.turretPitch = MathHelper.clamp(st.turretPitch + pitchDelta, -90f, 90f);
    }

    /** Reconstructs a look vector from a player's eased turret yaw/pitch. Vec3d.fromPolar() is
     *  the same vanilla conversion Entity.getRotationVector() itself uses internally (confirmed
     *  present on this project's mapped 1.20.1 API via javap — not reimplemented from memory). */
    private static Vec3d smoothedLookVec(PlayerState st) {
        return Vec3d.fromPolar(st.turretPitch, st.turretYaw);
    }

    /** A turret slot's fixed center point (no ring offset) — shared by conjureOne()'s pre-spawn
     *  positioning and positionTurret()'s per-tick repositioning. `look` is the caller's already-
     *  computed (eased) look direction, not re-derived from the player here. */
    private static Vec3d turretSlotCenter(ServerPlayerEntity player, Slot slot, Vec3d look) {
        Vec3d eye  = player.getEyePos();
        Vec3d worldUp = new Vec3d(0, 1, 0);
        Vec3d refUp   = Math.abs(look.y) > 0.999 ? new Vec3d(0, 0, 1) : worldUp;
        Vec3d right   = look.crossProduct(refUp).normalize();
        Vec3d up      = right.crossProduct(look).normalize();
        double[] signs = slotSigns(slot);
        return eye.add(look.multiply(TURRET_FORWARD_DIST))
            .add(right.multiply(signs[0] * TURRET_SIDE))
            .add(up.multiply(signs[1] * TURRET_VERT));
    }

    /** One back-ring turret's center — yaw-relative (not pitch-tied, same convention as
     *  barrageWorldPos()'s frame), on a ring behind the player that expands with `total` and
     *  slowly orbits as a whole (BACK_RING_ORBIT_SPEED). `yawDeg` is the caller's already-eased
     *  turret yaw, not re-read from the player here. */
    private static Vec3d backRingSlotCenter(ServerPlayerEntity player, int index, int total, float yawDeg) {
        double yaw = Math.toRadians(yawDeg);
        Vec3d right = new Vec3d(Math.cos(yaw), 0, Math.sin(yaw));
        Vec3d back  = new Vec3d(Math.sin(yaw), 0, -Math.cos(yaw));
        Vec3d up    = new Vec3d(0, 1, 0);
        Vec3d ringCenter = player.getEyePos().add(back.multiply(BACK_RING_DISTANCE));

        int n = Math.max(1, total);
        double ringRadius = BACK_RING_BASE_RADIUS + (n - 1) * BACK_RING_RADIUS_STEP;
        double backRingAngle = tickCount * BACK_RING_ORBIT_SPEED;
        double angle = index * (2 * Math.PI / n) + backRingAngle;
        double cosA = Math.cos(angle) * ringRadius, sinA = Math.sin(angle) * ringRadius;
        return ringCenter.add(right.multiply(cosA)).add(up.multiply(sinA));
    }

    /** Dispatches to the front-slot or back-ring center depending on which kind of turret this
     *  group is — shared by positionTurret()'s per-tick call and conjureOne()'s pre-spawn
     *  positioning (the session-40 spin-glitch fix), so both stay consistent automatically. */
    private static Vec3d groupCenter(ServerPlayerEntity player, ArrowGroup g, Vec3d look, float yawDeg) {
        return g.slot != null ? turretSlotCenter(player, g.slot, look) : backRingSlotCenter(player, g.backRingIndex, g.backRingTotal, yawDeg);
    }

    // ── Public API — formation / fire mode selection ──────────────────────────

    /** Sets the player's current arrow formation. Switching to/from CIRCLES rebuilds the
     *  group list (existing arrows in the old shared group are kept in a single CIRCLES slot
     *  rather than discarded, so switching formation mid-flight doesn't lose arrows). count
     *  (1-4) only applies to CIRCLES. */
    public static String setFormation(MinecraftServer server, String playerName, String formationName, int count) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.autoSpawnPaused = false;
            Formation formation;
            try { formation = Formation.valueOf(formationName.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return "{\"error\":\"unknown formation\"}"; }

            if (formation == Formation.CIRCLES) {
                int n = Math.max(1, Math.min(7, count)); // was capped at 4 — 5-7 go to the back ring, see below
                // Flatten every existing group's arrows first (covers both "was CIRCLES with a
                // different count" and "was MAGE_CIRCLE/BARRAGE's single group") so re-slotting
                // never silently strands arrows in a group that's about to be discarded — those
                // would otherwise only get cleaned up later by the orphan sweep.
                List<UUID> carry = new ArrayList<>();
                for (ArrowGroup g : st.groups) carry.addAll(g.arrows);

                int frontCount = Math.min(4, n);
                Slot[] slots = resolveSlots(st, frontCount); // unchanged front logic, never asked for >4
                List<ArrowGroup> newGroups = new ArrayList<>();
                for (int i = 0; i < frontCount; i++) newGroups.add(new ArrowGroup(slots[i]));

                int backCount = n - frontCount; // 0 unless n > 4
                for (int i = 0; i < backCount; i++) {
                    ArrowGroup g = new ArrowGroup(null);
                    g.backRingIndex = i;
                    g.backRingTotal = backCount;
                    newGroups.add(g);
                }

                for (int i = 0; i < carry.size(); i++) newGroups.get(i % n).arrows.add(carry.get(i));
                st.groups = newGroups;
                st.circleCount = n;
            } else if (st.formation == Formation.CIRCLES) {
                // Leaving CIRCLES — merge every turret's arrows back into one shared group.
                ArrowGroup merged = new ArrowGroup(null);
                for (ArrowGroup g : st.groups) merged.arrows.addAll(g.arrows);
                st.groups = new ArrayList<>(List.of(merged));
            }
            st.formation = formation;
            return "{\"ok\":true,\"formation\":\"" + formation + "\",\"count\":" + st.circleCount + "}";
        });
    }

    /** Per-circle-index slot override (independent of the current formation/count — applies
     *  next time that index is active). Also retargets an already-active circle immediately. */
    public static String setCircleSlot(MinecraftServer server, String playerName, int index, String slotName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            if (index < 0 || index >= 4) return "{\"error\":\"index must be 0-3\"}";
            Slot slot;
            try { slot = Slot.valueOf(slotName.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return "{\"error\":\"unknown slot\"}"; }
            st.slotOverride[index] = slot;
            if (st.formation == Formation.CIRCLES && index < st.groups.size()) {
                st.groups.get(index).slot = slot;
            }
            return "{\"ok\":true,\"index\":" + index + ",\"slot\":\"" + slot + "\"}";
        });
    }

    /** Barrage spawn spacing preset — see SpreadPreset. */
    public static String setBarrageSpread(MinecraftServer server, String playerName, String presetName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            SpreadPreset preset;
            try { preset = SpreadPreset.valueOf(presetName.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return "{\"error\":\"unknown preset\"}"; }
            st.barrageSpread = preset;
            return "{\"ok\":true,\"preset\":\"" + preset + "\"}";
        });
    }

    /** CIRCLES fire-mode behavior: simultaneous (default, every circle independent/parallel) vs
     *  sequential (one shared round-robin turn order) — see driveSequentialCircles(). */
    public static String setCirclesSequential(MinecraftServer server, String playerName, boolean enabled) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.circlesSequential = enabled;
            return "{\"ok\":true,\"circlesSequential\":" + enabled + "}";
        });
    }

    /** Rain arrow count (1-500) — the "power" knob; more arrows costs more hunger, see
     *  castRain()/payRainCost(). */
    public static String setRainCount(MinecraftServer server, String playerName, int count) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.rainCount = Math.max(1, Math.min(500, count));
            return "{\"ok\":true,\"rainCount\":" + st.rainCount + "}";
        });
    }

    public static String setRainTarget(MinecraftServer server, String playerName, String mode) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.rainTargetSelf = !"aim".equalsIgnoreCase(mode);
            return "{\"ok\":true,\"target\":\"" + (st.rainTargetSelf ? "self" : "aim") + "\"}";
        });
    }

    public static String setRainLifeDrain(MinecraftServer server, String playerName, boolean enabled) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.rainLifeDrainEnabled = enabled;
            return "{\"ok\":true,\"lifeDrain\":" + enabled + "}";
        });
    }

    /** Sets the player's current fire mode. Always clears any in-flight burst/machine-gun
     *  state on every group first, so switching modes never leaves a stale drain running. */
    public static String setFireMode(MinecraftServer server, String playerName, String modeName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.autoSpawnPaused = false;
            FireMode mode;
            try { mode = FireMode.valueOf(modeName.toUpperCase(Locale.ROOT)); }
            catch (Exception e) { return "{\"error\":\"unknown fire mode\"}"; }
            for (ArrowGroup g : st.groups) { g.mgActive = false; g.burstRemaining = 0; }
            st.fireMode = mode;
            return "{\"ok\":true,\"fireMode\":\"" + mode + "\"}";
        });
    }

    // ── Public API — release ───────────────────────────────────────────────────

    /** Applies the player's current fire mode to every relevant group. SHOTGUN releases
     *  immediately; BURST/MACHINE_GUN arm a tick()-driven drain (see class doc). If nothing at
     *  all is manifested, conjures and immediately fires one arrow so shoot always does
     *  something. */
    public static String shoot(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            if (st.formation == Formation.RAIN) return castRain(player, st);
            if (totalArrows(st) == 0) {
                ArrowGroup group = groupForNewArrow(st);
                conjureOne(player, st, group);
            }
            int affected = 0;
            for (ArrowGroup g : st.groups) {
                if (g.arrows.isEmpty()) continue;
                switch (st.fireMode) {
                    case SHOTGUN -> affected += releaseAll(server, player, st, g);
                    case BURST -> { g.burstRemaining = Math.min(BURST_COUNT, g.arrows.size()); g.burstCooldown = 0; affected++; }
                    case MACHINE_GUN -> { g.mgActive = true; affected++; }
                    case SINGLE -> { releaseOne(player, g, buildFireContext(player, st)); affected++; }
                }
            }
            return "{\"ok\":true,\"mode\":\"" + st.fireMode + "\",\"groups\":" + affected + "}";
        });
    }

    /** Releases every arrow in one group at once (SHOTGUN, and BURST/MACHINE_GUN's per-arrow
     *  release both funnel through this for one arrow at a time via releaseOne()). Returns 1
     *  (kept boolean-ish for shoot()'s "affected" count). */
    private static int releaseAll(MinecraftServer server, ServerPlayerEntity player, PlayerState st, ArrowGroup g) {
        List<UUID> ids = new ArrayList<>(g.arrows);
        g.arrows.clear();
        ServerWorld world = player.getServerWorld();
        FireContext ctx = buildFireContext(player, st);
        for (UUID id : ids) releaseArrow(world, player, id, ctx);
        return 1;
    }

    /** Releases just the OLDEST arrow in a group (BURST/MACHINE_GUN, driven from tick()). */
    private static void releaseOne(ServerPlayerEntity player, ArrowGroup g, FireContext ctx) {
        if (g.arrows.isEmpty()) return;
        UUID id = g.arrows.remove(0);
        releaseArrow(player.getServerWorld(), player, id, ctx);
    }

    private record FireContext(Vec3d look, double speed, boolean flame, int knockback, int piercing,
                                double flightSpeedMultiplier) {}

    private static FireContext buildFireContext(ServerPlayerEntity player, PlayerState st) {
        Vec3d look = player.getRotationVector();
        double speed = (player.getHungerManager().getFoodLevel() > 0
            ? ARROW_SPEED : ARROW_SPEED * HUNGER_SPEED_MULTIPLIER) * st.flightSpeedMultiplier;
        Set<Identifier> enchants = SummonEnchantTracker.getActiveSet(player.getUuid(), "arrow");
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
        return new FireContext(look, speed, flame, knockback, piercing, st.flightSpeedMultiplier);
    }

    private static void releaseArrow(ServerWorld world, ServerPlayerEntity player, UUID id, FireContext ctx) {
        // A fired arrow was actually used — drop its hunger-refund eligibility (never refund
        // an arrow that got shot, only ones dismissed unused).
        Set<UUID> funded = hungerFundedArrows.get(player.getUuid());
        if (funded != null) funded.remove(id);

        // Speed regulator's flight-speed cost — release itself never cost hunger before this;
        // "the bigger the speed the bigger the chance" applies here too, independent of the
        // conjure-time chance. Same foodLevel>0 gating as the earlier free-food bugfix — only
        // ever charges a REAL point, never a clamped no-op that would need refunding later
        // (there's no release-side refund path, so this one has to be correct up front).
        double releaseChance = Math.min(1.0, RELEASE_HUNGER_CHANCE_BASE * ctx.flightSpeedMultiplier());
        if (RANDOM.nextDouble() < releaseChance) {
            var hunger = player.getHungerManager();
            if (hunger.getFoodLevel() > 0) hunger.setFoodLevel(hunger.getFoodLevel() - 1);
        }

        if (world.getEntity(id) instanceof ArrowEntity arrow) {
            // Snap to a safe muzzle point just in front of the eyes before re-enabling
            // collision. BUG FIX: a BARRAGE arrow (spawned behind the player) firing forward
            // used to pass back through the player's own hitbox right as noClip turned off —
            // confirmed live, self-hit that killed the player. Applied unconditionally (not
            // just for Barrage) since it's a no-op-looking single-frame jump for formations
            // already in front, and closes off the same risk for any future formation.
            Vec3d muzzle = player.getEyePos().add(ctx.look().multiply(0.5));
            arrow.setPosition(muzzle.x, muzzle.y, muzzle.z);
            arrow.setNoClip(false);
            arrow.setNoGravity(false);
            arrow.pickupType = PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
            arrow.setVelocity(ctx.look().x * ctx.speed(), ctx.look().y * ctx.speed(), ctx.look().z * ctx.speed());
            if (ctx.flame())          arrow.setOnFireFor(100);
            if (ctx.knockback() > 0)  arrow.setPunch(ctx.knockback());
            if (ctx.piercing()  > 0)  arrow.setPierceLevel((byte) ctx.piercing());
        }
    }

    /** Discard every manifested arrow (every group) without shooting. Refunds hunger for any
     *  that were hunger-funded and never fired. */
    public static String dismiss(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            PlayerState st = states.get(player.getUuid());
            if (st == null) return "{\"ok\":true}";
            // BUG FIX: dismiss used to only clear the current arrows, but Barrage/Circles'
            // auto-conjure timer kept running regardless and just refilled the group on the
            // next tick — from the player's side, "dismiss doesn't work." Now pauses the
            // auto-conjure loop itself; any explicit "give me more" action (manifest, formation/
            // fire-mode change) resumes it.
            st.autoSpawnPaused = true;
            ServerWorld world = player.getServerWorld();
            Set<UUID> funded = hungerFundedArrows.remove(player.getUuid());
            int refunded = 0;
            for (ArrowGroup g : st.groups) {
                for (UUID id : g.arrows) {
                    var e = world.getEntity(id);
                    if (e != null) e.discard();
                    if (funded != null && funded.contains(id)) {
                        var hunger = player.getHungerManager();
                        hunger.setFoodLevel(Math.min(20, hunger.getFoodLevel() + 1));
                        refunded++;
                    }
                }
                g.arrows.clear();
                g.mgActive = false;
                g.burstRemaining = 0;
            }
            st.barrageOffsets.clear();
            return "{\"ok\":true,\"refunded\":" + refunded + "}";
        });
    }

    // ── RAIN — a one-shot skill cast, not a persistent formation ────────────────────────────

    /** Casts Arrow Rain (formation must already be RAIN — checked by shoot()). Resolves the
     *  target center, charges the resource cost, and arms the staggered spawn queue
     *  tickRainSpawn() drains from tick(). Never blocks on insufficient resources — see
     *  payRainCost(). */
    private static String castRain(ServerPlayerEntity player, PlayerState st) {
        if (st.rainCooldownTicks > 0) {
            return "{\"ok\":false,\"cooldown\":true,\"ticksLeft\":" + st.rainCooldownTicks + "}";
        }

        if (st.rainTargetSelf) {
            st.rainCenter = player.getPos();
        } else {
            Vec3d eye = player.getEyePos();
            Vec3d look = player.getRotationVector();
            // Flat eye+look projection, not a real block raycast — deliberately simple, since
            // arrows fall with real gravity from a fixed height regardless of target mode and
            // will land wherever terrain actually is without needing to know ground height in
            // advance (see spawnRainArrow()).
            st.rainCenter = new Vec3d(eye.x + look.x * RAIN_AIM_RANGE, eye.y, eye.z + look.z * RAIN_AIM_RANGE);
        }

        int cost = (int) Math.ceil(st.rainCount / (double) RAIN_HUNGER_DIVISOR);
        int paid = payRainCost(player, cost, st.rainLifeDrainEnabled);

        st.rainSpawnRemaining = st.rainCount;
        st.rainSpawnCooldown  = 0;
        st.rainCooldownTicks  = RAIN_COOLDOWN_TICKS;

        return "{\"ok\":true,\"count\":" + st.rainCount + ",\"cost\":" + cost + ",\"paid\":" + paid + "}";
    }

    /** Pays up to `cost` hunger points, then — if lifeDrainEnabled — health beyond that, but
     *  never past RAIN_LIFE_HEALTH_FLOOR of max health. Anything still unpaid after both is
     *  simply forgiven; Rain always fires once cast, matching this class's existing "never
     *  block the action" cost philosophy (see conjureOne()'s NO_ARROW_HUNGER_CHANCE). Returns
     *  how much was actually paid. */
    private static int payRainCost(ServerPlayerEntity player, int cost, boolean lifeDrainEnabled) {
        if (cost <= 0) return 0;
        var hunger = player.getHungerManager();
        int fromHunger = Math.min(cost, hunger.getFoodLevel());
        if (fromHunger > 0) hunger.setFoodLevel(hunger.getFoodLevel() - fromHunger);
        int remaining = cost - fromHunger;
        int fromLife = 0;
        if (remaining > 0 && lifeDrainEnabled) {
            float floor = player.getMaxHealth() * (float) RAIN_LIFE_HEALTH_FLOOR;
            float available = Math.max(0f, player.getHealth() - floor);
            fromLife = (int) Math.min(remaining, Math.floor(available));
            if (fromLife > 0) player.setHealth(player.getHealth() - fromLife);
        }
        return fromHunger + fromLife;
    }

    /** Drains st.rainSpawnRemaining one arrow at a time on RAIN_SPAWN_INTERVAL_TICKS — same
     *  staggered pattern autoConjure() uses for Barrage, so a cast reads as "raining" over a
     *  few seconds instead of dumping the whole count in one tick. Also ticks the cast
     *  cooldown down, unconditionally, regardless of current formation (switching away from
     *  RAIN doesn't pause the cooldown). */
    private static void tickRainSpawn(ServerPlayerEntity player, PlayerState st) {
        if (st.rainCooldownTicks > 0) st.rainCooldownTicks--;
        if (st.rainSpawnRemaining <= 0) return;
        if (st.rainSpawnCooldown > 0) { st.rainSpawnCooldown--; return; }
        st.rainSpawnCooldown = RAIN_SPAWN_INTERVAL_TICKS;
        spawnRainArrow(player, st);
        st.rainSpawnRemaining--;
    }

    /** Spawns one REAL, gravity-affected arrow — unlike every other arrow group in this class,
     *  which floats with noClip until fired, Rain arrows fall and collide from the moment
     *  they're spawned, so a target can actually dodge (per the user's explicit ask). Tagged
     *  RAIN_ARROW_TAG for both the short-lived lifetime sweep (tickRainArrowLifetimes()) and
     *  RainOwnerImmunityMixin's owner-immunity check. */
    private static void spawnRainArrow(ServerPlayerEntity player, PlayerState st) {
        ServerWorld world = player.getServerWorld();
        Vec3d center = st.rainCenter != null ? st.rainCenter : player.getPos();

        double sx = center.x + (RANDOM.nextDouble() * 2 - 1) * RAIN_RADIUS;
        double sz = center.z + (RANDOM.nextDouble() * 2 - 1) * RAIN_RADIUS;
        double sy = player.getEyePos().y + RAIN_SPAWN_HEIGHT;

        double dmg = ARROW_DAMAGE;
        Set<Identifier> enchants = SummonEnchantTracker.getActiveSet(player.getUuid(), "arrow");
        Identifier powerId = new Identifier("minecraft", "power");
        if (enchants.contains(powerId)) {
            Enchantment powerEnch = Registries.ENCHANTMENT.get(powerId);
            if (powerEnch != null) dmg += powerEnch.getMaxLevel() * 0.5 + 0.5;
        }

        ArrowEntity arrow = new ArrowEntity(world, player);
        arrow.setPosition(sx, sy, sz);
        double vx = (RANDOM.nextDouble() * 2 - 1) * RAIN_SCATTER_ANGLE;
        double vz = (RANDOM.nextDouble() * 2 - 1) * RAIN_SCATTER_ANGLE;
        arrow.setVelocity(vx, -RAIN_FALL_SPEED, vz);
        arrow.setDamage(dmg);
        arrow.setNoGravity(false);
        arrow.setNoClip(false);
        arrow.pickupType = PersistentProjectileEntity.PickupPermission.CREATIVE_ONLY;
        arrow.addCommandTag(RAIN_ARROW_TAG);
        world.spawnEntity(arrow);

        rainArrowSpawnTick.put(arrow.getUuid(), tickCount);
    }

    /** Force-discards any RAIN_ARROW_TAG arrow older than RAIN_ARROW_LIFETIME_TICKS, regardless
     *  of whether it landed or hit something — age-based is simpler and more robust than
     *  detecting "on ground," and keeps concurrent entity count bounded even for a large
     *  rainCount (spawns are staggered too — see tickRainSpawn() — so worst-case concurrent
     *  count is roughly spawn-rate × lifetime, never the full cast count at once). Global, not
     *  per-player — runs once per server tick from tick(). */
    private static void tickRainArrowLifetimes(MinecraftServer server) {
        if (rainArrowSpawnTick.isEmpty()) return;
        var it = rainArrowSpawnTick.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (tickCount - entry.getValue() < RAIN_ARROW_LIFETIME_TICKS) continue;
            for (ServerWorld world : server.getWorlds()) {
                var e = world.getEntity(entry.getKey());
                if (e != null) { e.discard(); break; }
            }
            it.remove();
        }
    }

    public static String setFireRate(MinecraftServer server, String playerName, double multiplier) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.fireRateMultiplier = Math.max(SPEED_MULTIPLIER_MIN, Math.min(SPEED_MULTIPLIER_MAX, multiplier));
            return "{\"ok\":true,\"fireRate\":" + st.fireRateMultiplier + "}";
        });
    }

    public static String setFlightSpeed(MinecraftServer server, String playerName, double multiplier) {
        return dispatch(server, playerName, player -> {
            PlayerState st = stateFor(player.getUuid());
            st.flightSpeedMultiplier = Math.max(SPEED_MULTIPLIER_MIN, Math.min(SPEED_MULTIPLIER_MAX, multiplier));
            return "{\"ok\":true,\"flightSpeed\":" + st.flightSpeedMultiplier + "}";
        });
    }

    public static String setRuneSpell(MinecraftServer server, String playerName, boolean enabled) {
        return dispatch(server, playerName, player -> {
            stateFor(player.getUuid()).runeSpellEnabled = enabled;
            return "{\"ok\":true,\"runeSpell\":" + enabled + "}";
        });
    }

    public static String setRuneAura(MinecraftServer server, String playerName, boolean enabled) {
        return dispatch(server, playerName, player -> {
            stateFor(player.getUuid()).runeAuraEnabled = enabled;
            return "{\"ok\":true,\"runeAura\":" + enabled + "}";
        });
    }

    /** Spawns one ENCHANT particle per enabled ring per tick, advancing a shared rotation
     *  phase — over roughly a second this traces out a visible circle, same "individual
     *  floating rune" look vanilla's own enchant table ambient effect has, far cheaper than
     *  spawning a full ring of particles every tick. No-ops instantly if neither toggle is on
     *  (the common case), so this is cheap to call unconditionally from tick(). */
    private static void tickRuneEffects(ServerWorld world, ServerPlayerEntity player, PlayerState st) {
        if (!st.runeSpellEnabled && !st.runeAuraEnabled) return;
        st.runeAngle += RUNE_ROTATION_SPEED;
        double cosA = Math.cos(st.runeAngle), sinA = Math.sin(st.runeAngle);

        if (st.runeSpellEnabled) {
            Vec3d eye  = player.getEyePos();
            Vec3d look = player.getRotationVector();
            Vec3d center = eye.add(look.multiply(FORWARD_DIST));
            Vec3d worldUp = new Vec3d(0, 1, 0);
            Vec3d refUp   = Math.abs(look.y) > 0.999 ? new Vec3d(0, 0, 1) : worldUp;
            Vec3d right   = look.crossProduct(refUp).normalize();
            Vec3d up      = right.crossProduct(look).normalize();
            Vec3d pos = center.add(right.multiply(cosA * RUNE_SPELL_RADIUS)).add(up.multiply(sinA * RUNE_SPELL_RADIUS));
            world.spawnParticles(ParticleTypes.ENCHANT, pos.x, pos.y, pos.z, 1, 0, 0, 0, 0.0);
        }
        if (st.runeAuraEnabled) {
            Vec3d p = player.getPos();
            double ax = p.x + cosA * RUNE_AURA_RADIUS, az = p.z + sinA * RUNE_AURA_RADIUS;
            world.spawnParticles(ParticleTypes.ENCHANT, ax, p.y + player.getHeight() * 0.5, az, 1, 0, 0, 0, 0.0);
        }
    }

    /** Sets/gets which skill MasterWheelScreen's "Skill" wedge casts. Generic-shaped (a plain
     *  String, not a Rain-specific flag) even though only one skill exists yet — see the plan's
     *  note on why this is real infrastructure now, not deferred. */
    public static String setEquippedSkill(MinecraftServer server, String playerName, String skill) {
        return dispatch(server, playerName, player -> {
            equippedSkill.put(player.getUuid(), skill);
            return "{\"ok\":true,\"skill\":\"" + skill + "\"}";
        });
    }

    public static String getState(MinecraftServer server, String playerName) {
        var player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"count\":0}";
        PlayerState st = states.get(player.getUuid());
        String skill = equippedSkill.getOrDefault(player.getUuid(), "arrow_rain");
        if (st == null) {
            return "{\"count\":0,\"formation\":\"MAGE_CIRCLE\",\"fireMode\":\"SHOTGUN\",\"barrageSpread\":\"NORMAL\""
                + ",\"slots\":[],\"rainCount\":100,\"rainTarget\":\"self\",\"rainLifeDrain\":false"
                + ",\"rainCooldownTicks\":0,\"equippedSkill\":\"" + skill + "\""
                + ",\"runeSpell\":false,\"runeAura\":false,\"fireRate\":1.0,\"flightSpeed\":1.0"
                + ",\"circlesSequential\":false}";
        }
        // Only front (Slot-based) turrets are listed — back-ring turrets (5th+ circle) have no
        // individually-configurable slot, they're auto-arranged on the ring (see groupCenter()).
        StringBuilder slots = new StringBuilder("[");
        if (st.formation == Formation.CIRCLES) {
            boolean first = true;
            for (ArrowGroup g : st.groups) {
                if (g.slot == null) continue;
                if (!first) slots.append(',');
                first = false;
                slots.append('"').append(g.slot).append('"');
            }
        }
        slots.append(']');
        return "{\"count\":" + totalArrows(st) + ",\"formation\":\"" + st.formation
            + "\",\"fireMode\":\"" + st.fireMode + "\",\"circleCount\":" + st.circleCount
            + ",\"barrageSpread\":\"" + st.barrageSpread + "\",\"slots\":" + slots
            + ",\"rainCount\":" + st.rainCount + ",\"rainTarget\":\"" + (st.rainTargetSelf ? "self" : "aim")
            + "\",\"rainLifeDrain\":" + st.rainLifeDrainEnabled + ",\"rainCooldownTicks\":" + st.rainCooldownTicks
            + ",\"equippedSkill\":\"" + skill + "\""
            + ",\"runeSpell\":" + st.runeSpellEnabled + ",\"runeAura\":" + st.runeAuraEnabled
            + ",\"fireRate\":" + st.fireRateMultiplier + ",\"flightSpeed\":" + st.flightSpeedMultiplier
            + ",\"circlesSequential\":" + st.circlesSequential + "}";
    }

    public static void clearPlayer(UUID uuid) {
        states.remove(uuid);
        hungerFundedArrows.remove(uuid);
        lastHungryManifestTick.remove(uuid);
        equippedSkill.remove(uuid);
    }

    public static void onServerStart() {
        states.clear();
        hungerFundedArrows.clear();
        lastHungryManifestTick.clear();
        equippedSkill.clear();
        rainArrowSpawnTick.clear();
        tickCount = 0;
        sweepCounter = 0;
    }

    // ── Tick — reposition every group, drive auto-conjure + burst/machine-gun drains ──────

    public static void tick(MinecraftServer server) {
        // Runs unconditionally, regardless of whether anyone currently has an active state —
        // that's exactly the state right after a restart, before anyone conjures again, which
        // is when orphan cleanup matters most.
        if (++sweepCounter >= ORPHAN_SWEEP_TICKS) {
            sweepCounter = 0;
            sweepOrphans(server);
        }
        tickRainArrowLifetimes(server);

        if (states.isEmpty()) return;
        double t = ++tickCount * ROTATION_SPEED;

        var iter = states.entrySet().iterator();
        while (iter.hasNext()) {
            var entry = iter.next();
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            PlayerState st = entry.getValue();
            if (player == null) {
                discardAllGroups(server, st);
                iter.remove();
                continue;
            }

            ServerWorld world = player.getServerWorld();

            // Prune arrows that were picked up or naturally despawned, in every group.
            for (ArrowGroup g : st.groups) {
                g.arrows.removeIf(id -> { var e = world.getEntity(id); return e == null || e.isRemoved(); });
            }

            autoConjure(player, st);
            positionGroups(world, player, st, t);
            driveFireModes(player, st);
            tickRainSpawn(player, st);
            tickRuneEffects(world, player, st);
        }
    }

    /** BARRAGE and CIRCLES both auto-conjure on a timer; MAGE_CIRCLE stays manual-only
     *  (unchanged from the original single-formation behavior). */
    private static void autoConjure(ServerPlayerEntity player, PlayerState st) {
        if (st.formation == Formation.MAGE_CIRCLE || st.formation == Formation.RAIN) return;
        if (st.autoSpawnPaused) return;
        if (st.barrageSpawnCooldown > 0) { st.barrageSpawnCooldown--; return; }
        st.barrageSpawnCooldown = Math.max(1, (int) Math.round(BARRAGE_SPAWN_INTERVAL_TICKS / st.fireRateMultiplier));

        ArrowGroup group = groupForNewArrow(st);
        int cap = st.formation == Formation.CIRCLES ? CIRCLE_MAX_ARROWS : MAX_ARROWS;
        if (group.arrows.size() >= cap) return;
        if (!checkHungryCooldown(player)) return;
        conjureOne(player, st, group);
    }

    /** Advances any armed BURST/MACHINE_GUN drains — one release per eligible group per its
     *  own cooldown, using the player's current look direction/enchants at the moment of each
     *  individual release (not frozen at the original /arrow-shoot call). */
    private static void driveFireModes(ServerPlayerEntity player, PlayerState st) {
        if (st.formation == Formation.CIRCLES && st.circlesSequential) {
            driveSequentialCircles(player, st);
            return;
        }
        boolean anyDue = false;
        for (ArrowGroup g : st.groups) {
            if (g.mgActive && !g.arrows.isEmpty()) {
                if (g.mgCooldown > 0) g.mgCooldown--; else anyDue = true;
            }
            if (g.burstRemaining > 0 && !g.arrows.isEmpty()) {
                if (g.burstCooldown > 0) g.burstCooldown--; else anyDue = true;
            }
        }
        if (!anyDue) return;

        FireContext ctx = buildFireContext(player, st);
        for (ArrowGroup g : st.groups) {
            if (g.mgActive && !g.arrows.isEmpty() && g.mgCooldown == 0) {
                releaseOne(player, g, ctx);
                g.mgCooldown = Math.max(1, (int) Math.round(MACHINE_GUN_INTERVAL_TICKS / st.fireRateMultiplier));
            }
            if (g.burstRemaining > 0 && !g.arrows.isEmpty() && g.burstCooldown == 0) {
                releaseOne(player, g, ctx);
                g.burstRemaining--;
                g.burstCooldown = BURST_INTERVAL_TICKS;
            }
        }
    }

    /** CIRCLES + circlesSequential: instead of every group draining on its OWN independent
     *  cooldown (the default, parallel behavior above), ONE shared cooldown/turn-index cycles
     *  through the groups round-robin, releasing exactly one shot from whichever eligible group
     *  is next in turn — confirmed: one shot per turn, not each circle emptying fully before the
     *  next starts. A group with burstRemaining still owed keeps taking turns until it runs out,
     *  same as mgActive groups keep taking turns until turned off or emptied. */
    private static void driveSequentialCircles(ServerPlayerEntity player, PlayerState st) {
        boolean anyEligible = false;
        for (ArrowGroup g : st.groups) {
            if ((g.mgActive || g.burstRemaining > 0) && !g.arrows.isEmpty()) { anyEligible = true; break; }
        }
        if (!anyEligible) return;
        if (st.sequentialCooldown > 0) { st.sequentialCooldown--; return; }

        int size = st.groups.size();
        for (int tries = 0; tries < size; tries++) {
            int idx = (st.sequentialTurnIndex + tries) % size;
            ArrowGroup g = st.groups.get(idx);
            boolean eligible = (g.mgActive || g.burstRemaining > 0) && !g.arrows.isEmpty();
            if (!eligible) continue;

            FireContext ctx = buildFireContext(player, st);
            releaseOne(player, g, ctx);
            if (g.burstRemaining > 0) g.burstRemaining--;
            st.sequentialTurnIndex = (idx + 1) % size;
            st.sequentialCooldown = Math.max(1, (int) Math.round(MACHINE_GUN_INTERVAL_TICKS / st.fireRateMultiplier));
            break;
        }
    }

    private static void discardAllGroups(MinecraftServer server, PlayerState st) {
        for (ArrowGroup g : st.groups) {
            for (UUID id : g.arrows) {
                for (ServerWorld w : server.getWorlds()) {
                    var e = w.getEntity(id);
                    if (e != null) { e.discard(); break; }
                }
            }
        }
    }

    // ── Positioning dispatch ───────────────────────────────────────────────────

    private static void positionGroups(ServerWorld world, ServerPlayerEntity player, PlayerState st, double t) {
        // Advance the eased turret angle every tick regardless of active formation, so it never
        // goes stale while BARRAGE/RAIN is active — see updateTurretAngle()'s doc comment.
        updateTurretAngle(player, st);
        switch (st.formation) {
            case MAGE_CIRCLE -> positionMageCircle(world, player, st, st.groups.get(0), t);
            case BARRAGE     -> positionBarrage(world, player, st);
            case CIRCLES     -> { for (ArrowGroup g : st.groups) positionTurret(world, player, st, g, t); }
            case RAIN        -> {} // no floating arrows to position — real falling projectiles, see castRain()
        }
    }

    /** BARRAGE: each arrow rides its own fixed random offset, rotated into the player's
     *  current yaw frame every tick so the swarm stays "behind" as the player turns. */
    private static void positionBarrage(ServerWorld world, ServerPlayerEntity player, PlayerState st) {
        ArrowGroup group = st.groups.get(0);
        if (group.arrows.isEmpty()) return;

        Vec3d look = player.getRotationVector();
        for (UUID id : group.arrows) {
            if (!(world.getEntity(id) instanceof ArrowEntity arrow)) continue;
            Vec3d off = st.barrageOffsets.get(id);
            if (off == null) { off = randomBarrageOffset(st.barrageSpread); st.barrageOffsets.put(id, off); }
            Vec3d pos = barrageWorldPos(player, off);
            arrow.setPosition(pos.x, pos.y, pos.z);
            arrow.setVelocity(-look.x * 0.02, look.y * 0.02, -look.z * 0.02); // same render-orientation trick as MAGE_CIRCLE
            arrow.velocityDirty = true;
            arrow.setNoGravity(true);
            arrow.setNoClip(true);
        }
    }

    /** CIRCLES: one small rotating ring per turret, fixed at its quadrant slot, aimed at
     *  wherever the player is currently looking (the slot offset is built from look direction,
     *  not yaw alone, so all turrets visibly converge toward the crosshair). Uses the player's
     *  EASED turret angle (see updateTurretAngle()), not their raw live look direction, so the
     *  whole formation swings toward a new facing instead of snapping to it. */
    private static void positionTurret(ServerWorld world, ServerPlayerEntity player, PlayerState st, ArrowGroup g, double t) {
        if (g.arrows.isEmpty()) return;

        Vec3d look = smoothedLookVec(st);
        Vec3d worldUp = new Vec3d(0, 1, 0);
        Vec3d refUp   = Math.abs(look.y) > 0.999 ? new Vec3d(0, 0, 1) : worldUp;
        Vec3d right   = look.crossProduct(refUp).normalize();
        Vec3d up      = right.crossProduct(look).normalize();

        Vec3d slotCenter = groupCenter(player, g, look, st.turretYaw);

        int n = g.arrows.size();
        for (int i = 0; i < n; i++) {
            if (!(world.getEntity(g.arrows.get(i)) instanceof ArrowEntity arrow)) continue;
            double angle = (n <= 1 ? 0.0 : 2 * Math.PI * i / n) + t;
            double cosA = Math.cos(angle) * TURRET_RADIUS, sinA = Math.sin(angle) * TURRET_RADIUS;
            Vec3d pos = slotCenter.add(right.multiply(cosA)).add(up.multiply(sinA));
            arrow.setPosition(pos.x, pos.y, pos.z);
            arrow.setVelocity(-look.x * 0.02, look.y * 0.02, -look.z * 0.02);
            arrow.velocityDirty = true;
            arrow.setNoGravity(true);
            arrow.setNoClip(true);
        }
    }

    // ── MAGE_CIRCLE formation math (unchanged from the original single-formation version) ──

    private static void positionMageCircle(ServerWorld world, ServerPlayerEntity player, PlayerState st, ArrowGroup group, double t) {
        List<UUID> list = group.arrows;
        if (list.isEmpty()) return;

        Vec3d eye  = player.getEyePos();
        Vec3d look = smoothedLookVec(st); // eased toward the player's live look, not a snap to it

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
            arrow.setVelocity(-look.x * 0.02, look.y * 0.02, -look.z * 0.02);
            arrow.velocityDirty = true;
            arrow.setNoGravity(true);
            arrow.setNoClip(true);
        }
    }

    private static int circleCountFor(int arrowCount) {
        return Math.min(MAX_CIRCLES, Math.max(1, (int) Math.ceil(arrowCount / (double) ARROWS_PER_CIRCLE)));
    }

    private static double circleRadius(int circle) {
        return RADIUS_BASE + circle * RADIUS_STEP;
    }

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

    private static Shape shapeOf(int circle) {
        if (circle == 1) return Shape.PLUS;
        if (circle == 2) return Shape.XCROSS;
        return Shape.ROUND;
    }

    private static Vec3d positionInFormation(int circle, int idxInCircle, int circleSize,
                                              Vec3d center, Vec3d look, Vec3d right, Vec3d ringUp, double t) {
        Shape shape = shapeOf(circle);

        double dir = shape == Shape.PLUS ? 1.0 : shape == Shape.XCROSS ? -1.0 : (circle % 2 == 0 ? 1.0 : -1.0);

        double baseRadius = circleRadius(circle);
        double angle, radius;
        if (shape != Shape.ROUND) {
            double baseOffset = shape == Shape.XCROSS ? Math.PI / 4.0 : 0.0;
            int spoke = idxInCircle % 4;
            int stack = idxInCircle / 4;
            angle  = baseOffset + spoke * (Math.PI / 2.0) + t * dir;
            radius = baseRadius + stack * SPOKE_STACK_GAP;
        } else {
            angle  = (circleSize <= 1 ? 0.0 : 2 * Math.PI * idxInCircle / circleSize) + t * dir;
            radius = baseRadius;
        }

        double azimuth = circle * GOLDEN_ANGLE;
        Vec3d azDir = right.multiply(Math.cos(azimuth)).add(ringUp.multiply(Math.sin(azimuth)));
        Vec3d formationCenter = circle == 0 ? center : center.add(azDir.multiply(baseRadius * POSITION_OFFSET_FRACTION));

        Vec3d planeRight = right, planeUp = ringUp;

        if (shape == Shape.ROUND && circle >= 3 && circle != ORBIT_CIRCLE_INDEX) {
            Vec3d tiltAxis = azDir.normalize();
            planeRight = rotateAroundAxis(right, tiltAxis, DIVERGE_TILT);
            planeUp    = rotateAroundAxis(ringUp, tiltAxis, DIVERGE_TILT);
        }

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

    private static Vec3d rotateAroundAxis(Vec3d v, Vec3d axis, double angle) {
        double cos = Math.cos(angle), sin = Math.sin(angle);
        return v.multiply(cos)
            .add(axis.crossProduct(v).multiply(sin))
            .add(axis.multiply(axis.dotProduct(v) * (1 - cos)));
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /** Finds any ORPHAN_TAG-tagged arrow not currently tracked by ANY player's state — left
     *  behind by a client crash or a full server restart — and discards it. */
    private static void sweepOrphans(MinecraftServer server) {
        Set<UUID> tracked = new HashSet<>();
        for (PlayerState st : states.values())
            for (ArrowGroup g : st.groups) tracked.addAll(g.arrows);

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
