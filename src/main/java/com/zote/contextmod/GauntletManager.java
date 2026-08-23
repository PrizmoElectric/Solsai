package com.zote.contextmod;

import com.zote.contextmod.mixin.GoalSelectorAccessor;
import com.zote.contextmod.mixin.MobEntityAccessor;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.ai.goal.ActiveTargetGoal;
import net.minecraft.entity.ai.goal.GoalSelector;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.ServerScoreboard;
import net.minecraft.scoreboard.Team;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Gauntlet Manifestation — summons the Iron Gauntlet (BOMD boss) as a player ally.
 *
 * The Gauntlet's default FindTargetGoal targets PlayerEntity.  To prevent it from
 * attacking the summoner two mechanisms are layered:
 *
 *   1. Scoreboard team  — summoner + gauntlet share a team with friendlyFire=false.
 *      EntityPredicate (used by ActiveTargetGoal) skips teammates automatically.
 *
 *   2. Goal replacement — the targetSelector is cleared of BOMD's FindTargetGoal and
 *      replaced with a vanilla ActiveTargetGoal that only targets hostile mob-group
 *      entities (excludes PlayerEntity, ArmorStand, and itself).
 *
 * Both layers must be present: the team check is the safety net if the goal
 * replacement ever fails (e.g. BOMD overrides canTrack in a future version).
 */
public class GauntletManager {

    private static final String TEAM_NAME = "prizmo_gauntlet_ally";

    private record GauntletState(UUID gauntletUUID, String gauntletScoreboardName) {}

    private static final Map<UUID, GauntletState> gauntlets = new ConcurrentHashMap<>();

    // ── Public API ────────────────────────────────────────────────────────────

    public static String summon(MinecraftServer server, String playerName) {
        return runOnServer(server, player -> {
            UUID playerUUID = player.getUuid();
            if (gauntlets.containsKey(playerUUID)) return "{\"error\":\"gauntlet already active\"}";

            var type = Registries.ENTITY_TYPE.get(
                new Identifier("bosses_of_mass_destruction", "gauntlet"));
            if (type == null) return "{\"error\":\"BOMD not loaded or gauntlet entity not found\"}";

            ServerWorld world = player.getServerWorld();
            Entity entity = type.create(world);
            if (entity == null) return "{\"error\":\"failed to create entity\"}";

            // Spawn 4 blocks in front of the player
            double yaw = Math.toRadians(player.getYaw());
            entity.setPosition(
                player.getX() - Math.sin(yaw) * 4.0,
                player.getY() + 1.0,
                player.getZ() + Math.cos(yaw) * 4.0
            );
            world.spawnEntity(entity);

            // ── Layer 1: scoreboard team ──────────────────────────────
            ServerScoreboard sc = server.getScoreboard();
            Team team = sc.getTeam(TEAM_NAME);
            if (team == null) {
                team = sc.addTeam(TEAM_NAME);
                team.setFriendlyFireAllowed(false);
            }
            String playerName2 = player.getName().getString();
            String gauntletName = entity.getEntityName();
            sc.addPlayerToTeam(playerName2, team);
            sc.addPlayerToTeam(gauntletName, team);

            // ── Layer 2: replace targetSelector goals ─────────────────
            if (entity instanceof MobEntity mob) {
                GoalSelector targetSel = ((MobEntityAccessor) mob).prizmo_getTargetSelector();
                @SuppressWarnings("rawtypes")
                Set existingGoals = ((GoalSelectorAccessor) targetSel).prizmo_getGoals();
                existingGoals.clear();

                final UUID gauntletUUID = entity.getUuid();
                targetSel.add(1, new ActiveTargetGoal<>(
                    mob, LivingEntity.class, 5, true, false,
                    e -> !(e instanceof PlayerEntity)
                      && !(e instanceof ArmorStandEntity)
                      && !e.getUuid().equals(gauntletUUID)
                      && e.getType().getSpawnGroup() == SpawnGroup.MONSTER
                ));
            }

            gauntlets.put(playerUUID, new GauntletState(entity.getUuid(), gauntletName));
            return "{\"ok\":true,\"entityName\":\"" + entity.getEntityName() + "\"}";
        }, playerName);
    }

    public static String dismiss(MinecraftServer server, String playerName) {
        return runOnServer(server, player -> {
            GauntletState state = gauntlets.remove(player.getUuid());
            if (state == null) return "{\"error\":\"no gauntlet active\"}";

            ServerWorld world = player.getServerWorld();
            Entity entity = world.getEntity(state.gauntletUUID());
            if (entity != null) entity.discard();

            cleanTeam(server, player.getName().getString(), state);
            return "{\"ok\":true}";
        }, playerName);
    }

    public static String getState(MinecraftServer server, String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"active\":false}";
        GauntletState state = gauntlets.get(player.getUuid());
        if (state == null) return "{\"active\":false}";
        ServerWorld world = player.getServerWorld();
        Entity entity = world.getEntity(state.gauntletUUID());
        if (entity == null || entity.isRemoved()) {
            gauntlets.remove(player.getUuid());
            return "{\"active\":false}";
        }
        return "{\"active\":true,\"hp\":" +
            (entity instanceof LivingEntity le ? le.getHealth() : -1) + "}";
    }

    // ── Server tick — prune dead gauntlets ────────────────────────────────────

    public static void tick(MinecraftServer server) {
        if (gauntlets.isEmpty()) return;
        gauntlets.entrySet().removeIf(entry -> {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(entry.getKey());
            if (player == null) return true;
            ServerWorld world = player.getServerWorld();
            Entity entity = world.getEntity(entry.getValue().gauntletUUID());
            if (entity == null || entity.isRemoved()) {
                cleanTeam(server, player.getName().getString(), entry.getValue());
                return true;
            }
            return false;
        });
    }

    public static void onServerStart() {
        gauntlets.clear();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static void cleanTeam(MinecraftServer server, String playerName, GauntletState state) {
        ServerScoreboard sc = server.getScoreboard();
        sc.clearPlayerTeam(state.gauntletScoreboardName());
        // Bug 5: previous condition was inverted — player was kept on team when other players had gauntlets.
        // Each player can have at most one gauntlet; after dismiss the player never needs the team entry.
        sc.clearPlayerTeam(playerName);
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
