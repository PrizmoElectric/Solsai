package com.zote.contextmod;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.projectile.ArrowEntity;
import net.minecraft.item.ArrowItem;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Arrow Manifestation — arrows float in a rotating ring in front of the player,
 * all pointing at the crosshair. On shoot, all are launched in the look direction.
 *
 * Orbital ring:
 *   - Center: player eye + look * FORWARD_DIST (always in front — never behind the player)
 *   - Plane: right (horizontal from yaw) × worldUp — robust, no singularity on look-up/down
 *   - Rotation: slow CW spin around the look axis so multiple arrows don't stack
 *   - 1 arrow: no ring, just center-front
 *
 * Arrow orientation: tiny velocity (0.02) in look direction each tick causes
 *   PersistentProjectileEntity to compute the correct visual yaw/pitch from the vector.
 *   noClip + noGravity prevent physics interference while floating.
 *
 * Cost: arrows consumed from inventory first; if none, addExhaustion (small hunger drain).
 */
public class ArrowManifestManager {

    private static final double FORWARD_DIST   = 1.8;   // blocks ahead of eye
    private static final double ORBIT_RADIUS   = 0.55;  // ring radius (used for N > 1)
    private static final double ROTATION_SPEED = 0.025; // radians/tick (~one turn per 4s)
    private static final double ARROW_SPEED    = 3.5;   // blocks/tick when released
    private static final double ARROW_DAMAGE   = 2.5;   // hit damage (× 0.5 = hearts)
    private static final int    MAX_ARROWS     = 12;
    private static final float  EXHAUSTION     = 3.0f;  // hunger exhaustion per conjured arrow
    private static int tickCount = 0;

    // playerUUID → ordered list of floating arrow UUIDs (order defines ring index)
    private static final Map<UUID, List<UUID>> manifested = new ConcurrentHashMap<>();

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Conjure one arrow: consume from inventory or drain exhaustion. */
    public static String manifest(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.computeIfAbsent(player.getUuid(), k -> new ArrayList<>());
            if (list.size() >= MAX_ARROWS) return "{\"error\":\"max " + MAX_ARROWS + " arrows\"}";

            // Consume physical arrow first; fall back to exhaustion
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
            if (!hadArrow) player.addExhaustion(EXHAUSTION);

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
            arrow.noClip = true;
            arrow.setDamage(dmg);
            world.spawnEntity(arrow);

            list.add(arrow.getUuid());
            String src = hadArrow ? "inventory" : "hunger";
            return "{\"ok\":true,\"count\":" + list.size() + ",\"from\":\"" + src + "\"}";
        });
    }

    /** Release all floating arrows at high speed in the player's look direction. */
    public static String shoot(MinecraftServer server, String playerName) {
        return dispatch(server, playerName, player -> {
            List<UUID> list = manifested.remove(player.getUuid());
            if (list == null || list.isEmpty()) return "{\"ok\":true,\"shot\":0}";
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
                    arrow.noClip = false;
                    arrow.setNoGravity(false);
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

    public static void onServerStart() { manifested.clear(); tickCount = 0; }

    // ── Tick — reposition each arrow in its orbital slot ──────────────────────

    public static void tick(MinecraftServer server) {
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

            // Horizontal right vector from yaw — no singularity at vertical look angles
            double yawRad = Math.toRadians(player.getYaw());
            double rx = -Math.cos(yawRad), rz = -Math.sin(yawRad); // right = (-cosYaw, 0, -sinYaw)

            int n = list.size();
            for (int i = 0; i < n; i++) {
                if (!(world.getEntity(list.get(i)) instanceof ArrowEntity arrow)) continue;

                double angle  = (n == 1 ? 0.0 : 2 * Math.PI * i / n) + t;
                double spread = (n == 1 ? 0.0 : ORBIT_RADIUS);

                // Ring offset: right×cos + worldUp×sin, both scaled by spread
                double ox = rx * Math.cos(angle) * spread;
                double oy = Math.sin(angle) * spread;         // worldUp.y = 1
                double oz = rz * Math.cos(angle) * spread;

                double px = eye.x + look.x * FORWARD_DIST + ox;
                double py = eye.y + look.y * FORWARD_DIST + oy;
                double pz = eye.z + look.z * FORWARD_DIST + oz;

                arrow.setPosition(px, py, pz);
                // Tiny velocity in look direction — causes the renderer to orient the
                // arrow model toward the crosshair (PersistentProjectileEntity derives
                // visual yaw/pitch from velocity direction each tick)
                arrow.setVelocity(look.x * 0.02, look.y * 0.02, look.z * 0.02);
                arrow.setNoGravity(true);
                arrow.noClip = true;
            }
        }
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
