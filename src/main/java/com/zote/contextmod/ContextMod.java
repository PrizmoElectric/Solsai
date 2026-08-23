package com.zote.contextmod;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class ContextMod implements ModInitializer {

    // Invisible block placed at each shield ArmorStand position to intercept the Gauntlet laser.
    // Full collision shape for raycasts (ShapeContext.absent), empty for entity movement.
    public static final ShieldBlock SHIELD_BLOCK = Registry.register(
        Registries.BLOCK,
        new Identifier("solsai", "shield_block"),
        new ShieldBlock()
    );

    // Player whose C2S packets are captured for mirror/recording
    public static final String MIRROR_PLAYER = "PrizmoElectric";

    // Thread-safe buffer drained by GET /mirror-events
    static final ConcurrentLinkedQueue<String> mirrorBuffer = new ConcurrentLinkedQueue<>();

    // Behavior mode pushed by Nilo via /bot-mode — key=playerName, value=mode string
    private static final ConcurrentHashMap<String, String> botModes = new ConcurrentHashMap<>();
    private static final int MAX_BUFFER = 2000;

    // Remote control state pushed by prizmo-system BotSneakScreen — key=playerName
    private static final ConcurrentHashMap<String, ControlState> controlStates = new ConcurrentHashMap<>();
    private static final long CONTROL_TIMEOUT_MS = 500;

    // Terminal commands queued for a bot — key=playerName, consumed by /terminal-command-state
    private static final ConcurrentHashMap<String, TerminalCommand> terminalCommands = new ConcurrentHashMap<>();
    private static final long TERMINAL_COMMAND_TIMEOUT_MS = 5000;

    private static class TerminalCommand {
        final String cmd;
        final long ts;
        TerminalCommand(String cmd, long ts) { this.cmd = cmd; this.ts = ts; }
    }

    private static class ControlState {
        boolean forward, back, left, right, jump, sneak, attack, use;
        float yaw, pitch;
        int heldSlot = -1; // -1 = no change requested; 0-8 = hotbar slot to select
        long ts;

        String toJson() {
            return String.format(Locale.US,
                "{\"active\":true,\"forward\":%b,\"back\":%b,\"left\":%b,\"right\":%b," +
                "\"jump\":%b,\"sneak\":%b,\"attack\":%b,\"use\":%b,\"yaw\":%.3f,\"pitch\":%.3f,\"heldSlot\":%d}",
                forward, back, left, right, jump, sneak, attack, use, yaw, pitch, heldSlot);
        }
    }

    public static void addMirrorEvent(String json) {
        if (mirrorBuffer.size() < MAX_BUFFER) mirrorBuffer.add(json);
    }

    private static MinecraftServer server;
    private HttpServer httpServer;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            ShieldManifestManager.onServerStart();
            CloneManager.onServerStart();
            GauntletManager.onServerStart();
            EnchantTracker.onServerStart();
            EffectTracker.onServerStart();
            ArrowManifestManager.onServerStart();
            startHttpServer();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            if (httpServer != null) httpServer.stop(0);
        });
        ServerTickEvents.END_SERVER_TICK.register(ShieldManifestManager::tick);
        ServerTickEvents.END_SERVER_TICK.register(CloneManager::tick);
        ServerTickEvents.END_SERVER_TICK.register(GauntletManager::tick);
        ServerTickEvents.END_SERVER_TICK.register(EffectTracker::tick);
        ServerTickEvents.END_SERVER_TICK.register(ArrowManifestManager::tick);
        // Clean per-player state on disconnect so nothing leaks across sessions
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server2) -> {
            var uuid = handler.player.getUuid();
            EnchantTracker.clearPlayer(uuid);
            EffectTracker.clearPlayer(uuid);
            ArrowManifestManager.clearPlayer(uuid);
        });
    }

    private void startHttpServer() {
        try {
            httpServer = HttpServer.create(new InetSocketAddress(8080), 0);

            httpServer.createContext("/context", exchange -> {
                sendJson(exchange, buildContext());
            });

            // GET /blocknames?sids=123,456,789
            // Returns {"123":"yigd:grave","456":"minecraft:stone",...}
            // Unknown stateIds are omitted. Ground-truth lookup via server registry.
            httpServer.createContext("/blocknames", exchange -> {
                String query = exchange.getRequestURI().getQuery();
                sendJson(exchange, buildBlockNames(query));
            });

            // GET /all-blocks — full server-side stateId → blockName registry.
            // Returns {"0":"minecraft:air","1":"minecraft:stone",...} for every
            // registered block state. Used to build an accurate viewer blockStates.
            httpServer.createContext("/all-blocks", exchange -> {
                sendJson(exchange, buildAllBlocks());
            });

            // GET /all-items — full server-side rawId → itemName registry.
            // Returns {"0":"minecraft:air","1":"minecraft:stone",...} for every item.
            httpServer.createContext("/all-items", exchange -> {
                sendJson(exchange, buildAllItems());
            });

            // GET /all-entities — full server-side rawId → entityTypeName registry.
            // Returns {"0":"minecraft:area_effect_cloud","1":"minecraft:armor_stand",...}
            // Numeric IDs match e.entityType in mineflayer.
            httpServer.createContext("/all-entities", exchange -> {
                sendJson(exchange, buildAllEntities());
            });

            // GET /bot-inventory?player=NILO — server-side inventory with full mod registry names.
            // Slot numbering: 0-8=hotbar, 9-35=main, 36=boots, 37=legs, 38=chest, 39=head, 40=offhand.
            httpServer.createContext("/bot-inventory", exchange -> {
                URI uri = exchange.getRequestURI();
                String query = uri.getQuery();
                String playerName = "NILO";
                if (query != null) {
                    for (String part : query.split("&")) {
                        if (part.startsWith("player=")) playerName = part.substring(7);
                    }
                }
                sendJson(exchange, buildBotInventory(playerName));
            });

            // GET /item-transfer?from=NILO&to=PrizmoElectric&slot=9&count=1
            // Moves items directly between player inventories on the server thread.
            // Slot numbering: 0-8=hotbar, 9-35=main, 36=boots, 37=legs, 38=chest, 39=head, 40=offhand.
            httpServer.createContext("/item-transfer", exchange -> {
                URI uri = exchange.getRequestURI();
                String q = uri.getQuery();
                String from = "", to = "";
                int slot = 0, count = 1;
                if (q != null) {
                    for (String part : q.split("&")) {
                        if (part.startsWith("from="))  from  = part.substring(5);
                        else if (part.startsWith("to="))   to    = part.substring(3);
                        else if (part.startsWith("slot="))  { try { slot  = Integer.parseInt(part.substring(5)); } catch (NumberFormatException ignored) {} }
                        else if (part.startsWith("count=")) { try { count = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) {} }
                    }
                }
                sendJson(exchange, buildItemTransfer(from, to, slot, count));
            });

            // GET /item-move?from=X&fromSlot=A&to=Y&toSlot=B&count=N
            // Moves items slot-to-slot. from/to can be the same player (rearrange).
            // Merges stacks of same type; swaps otherwise.
            httpServer.createContext("/item-move", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String from = "", to = "";
                int fromSlot = 0, toSlot = 0, count = Integer.MAX_VALUE;
                if (q != null) {
                    for (String part : q.split("&")) {
                        if      (part.startsWith("from="))     from     = part.substring(5);
                        else if (part.startsWith("to="))       to       = part.substring(3);
                        else if (part.startsWith("fromSlot=")) { try { fromSlot = Integer.parseInt(part.substring(9)); } catch (NumberFormatException ignored) {} }
                        else if (part.startsWith("toSlot="))   { try { toSlot   = Integer.parseInt(part.substring(8)); } catch (NumberFormatException ignored) {} }
                        else if (part.startsWith("count="))    { try { count    = Integer.parseInt(part.substring(6)); } catch (NumberFormatException ignored) {} }
                    }
                }
                sendJson(exchange, buildItemMove(from, fromSlot, to, toSlot, count));
            });

            // GET /bot-mode?player=NILO&mode=follow — receive behavior mode push from Nilo Node.js
            httpServer.createContext("/bot-mode", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String player = "NILO", mode = "idle";
                if (q != null) {
                    for (String part : q.split("&")) {
                        if      (part.startsWith("player=")) player = part.substring(7);
                        else if (part.startsWith("mode="))   mode   = part.substring(5);
                    }
                }
                botModes.put(player, mode);
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /bot-state?player=NILO — aggregated bot state for client mod HUD
            // Health and food come from ServerPlayerEntity; mode from botModes push.
            httpServer.createContext("/bot-state", exchange -> {
                String player = "NILO";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) {
                    for (String part : q.split("&")) {
                        if (part.startsWith("player=")) player = part.substring(7);
                    }
                }
                sendJson(exchange, buildBotState(player));
            });

            // GET /bot-control?player=NILO&forward=true&back=false&...&yaw=45.0&pitch=-10.0&heldSlot=3
            // Receives remote-control state from prizmo-system BotSneakScreen at ~10 Hz.
            // heldSlot (0-8) is optional — omitted/absent means "no change", not slot 0.
            // Stored with a timestamp; Nilo polls /bot-control-state to read it back.
            httpServer.createContext("/bot-control", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String player = "NILO";
                ControlState cs = new ControlState();
                if (q != null) {
                    for (String part : q.split("&")) {
                        if      (part.startsWith("player="))  player     = part.substring(7);
                        else if (part.startsWith("forward=")) cs.forward = "true".equals(part.substring(8));
                        else if (part.startsWith("back="))    cs.back    = "true".equals(part.substring(5));
                        else if (part.startsWith("left="))    cs.left    = "true".equals(part.substring(5));
                        else if (part.startsWith("right="))   cs.right   = "true".equals(part.substring(6));
                        else if (part.startsWith("jump="))    cs.jump    = "true".equals(part.substring(5));
                        else if (part.startsWith("sneak="))   cs.sneak   = "true".equals(part.substring(6));
                        else if (part.startsWith("attack="))  cs.attack  = "true".equals(part.substring(7));
                        else if (part.startsWith("use="))     cs.use     = "true".equals(part.substring(4));
                        else if (part.startsWith("yaw="))   { try { cs.yaw   = Float.parseFloat(part.substring(4)); } catch (NumberFormatException ignored) {} }
                        else if (part.startsWith("pitch=")) { try { cs.pitch = Float.parseFloat(part.substring(6)); } catch (NumberFormatException ignored) {} }
                        else if (part.startsWith("heldSlot=")) { try { cs.heldSlot = Integer.parseInt(part.substring(9)); } catch (NumberFormatException ignored) {} }
                    }
                }
                cs.ts = System.currentTimeMillis();
                controlStates.put(player, cs);
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /bot-control-state?player=NILO — Nilo polls this at ~10 Hz.
            // Returns the latest control state if fresh (<500 ms), else {"active":false}.
            httpServer.createContext("/bot-control-state", exchange -> {
                String player = "NILO";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) {
                    for (String part : q.split("&")) {
                        if (part.startsWith("player=")) player = part.substring(7);
                    }
                }
                ControlState cs = controlStates.get(player);
                if (cs == null || System.currentTimeMillis() - cs.ts > CONTROL_TIMEOUT_MS) {
                    sendJson(exchange, "{\"active\":false}");
                } else {
                    sendJson(exchange, cs.toJson());
                }
            });

            // GET /terminal-command?cmd=<urlencoded>[&player=NILO]
            // Commands starting with "/" run immediately via the server console
            // (full permission level — covers player/world manipulation: give, tp,
            // setblock, summon, weather, time, etc.). Anything else is queued for
            // the named bot (default NILO), which polls /terminal-command-state and
            // runs it through its existing chat-command parser.
            httpServer.createContext("/terminal-command", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String cmd = "", player = "NILO";
                if (q != null) {
                    for (String part : q.split("&")) {
                        if      (part.startsWith("cmd="))    cmd    = URLDecoder.decode(part.substring(4), StandardCharsets.UTF_8);
                        else if (part.startsWith("player=")) player = part.substring(7);
                    }
                }
                sendJson(exchange, runTerminalCommand(cmd, player));
            });

            // GET /terminal-command-state?player=NILO — Nilo polls this (~10 Hz).
            // Returns {"command":"..."} once per queued command, then {"command":null}.
            httpServer.createContext("/terminal-command-state", exchange -> {
                String q = exchange.getRequestURI().getQuery();
                String player = "NILO";
                if (q != null) {
                    for (String part : q.split("&")) {
                        if (part.startsWith("player=")) player = part.substring(7);
                    }
                }
                TerminalCommand tc = terminalCommands.remove(player);
                if (tc == null || System.currentTimeMillis() - tc.ts > TERMINAL_COMMAND_TIMEOUT_MS) {
                    sendJson(exchange, "{\"command\":null}");
                } else {
                    sendJson(exchange, "{\"command\":\"" + escapeJson(tc.cmd) + "\"}");
                }
            });

            // GET /clone-spawn?player=PrizmoElectric
            // Summons a "ghost clone" (vanilla Husk, owner-following meat-shield AI)
            // at the player's side. Costs the player 1 heart (2 HP) of magic damage.
            httpServer.createContext("/clone-spawn", exchange -> {
                String player = queryParam(exchange, "player", MIRROR_PLAYER);
                sendJson(exchange, withPlayer(player, CloneManager::spawnClone));
            });

            // GET /clone-despawn-all?player=PrizmoElectric
            // Discards every ghost clone owned by the player (current world only).
            httpServer.createContext("/clone-despawn-all", exchange -> {
                String player = queryParam(exchange, "player", MIRROR_PLAYER);
                sendJson(exchange, withPlayer(player, p ->
                    "{\"success\":true,\"despawned\":" + CloneManager.despawnAll(p) + "}"));
            });

            // GET /clone-list?player=PrizmoElectric
            // Returns [{"uuid":...,"x":...,"y":...,"z":...,"health":...,"maxHealth":...},...]
            // for prizmo-system to render ghost ESP overlays.
            httpServer.createContext("/clone-list", exchange -> {
                String player = queryParam(exchange, "player", MIRROR_PLAYER);
                sendJson(exchange, withPlayer(player, CloneManager::listClones, "[]"));
            });

            // GET /mirror-events — drain buffered C2S events captured by MirrorCaptureMixin.
            // Returns a JSON array and clears the buffer. Nilo polls this every 50ms.
            httpServer.createContext("/mirror-events", exchange -> {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                String ev;
                while ((ev = mirrorBuffer.poll()) != null) {
                    if (!first) sb.append(",");
                    sb.append(ev);
                    first = false;
                }
                sb.append("]");
                sendJson(exchange, sb.toString());
            });

            // GET /manifest-shield?player=X — summon one shield; dome redistributes automatically
            httpServer.createContext("/manifest-shield", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ShieldManifestManager.summon(server, player));
            });

            // GET /manifest-dome?player=X&count=N — instant dome of N shields (default 6)
            httpServer.createContext("/manifest-dome", exchange -> {
                String player = "PrizmoElectric";
                int count = 6;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("count=")) try {
                        count = Math.min(20, Math.max(1, Integer.parseInt(part.substring(6))));
                    } catch (NumberFormatException ignored) {}
                }
                sendJson(exchange, ShieldManifestManager.summonDome(server, player, count));
            });

            // GET /split-shield?player=X&count=N — consume player's shield, split durability into N (default 6)
            httpServer.createContext("/split-shield", exchange -> {
                String player = "PrizmoElectric";
                int count = 6;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("count=")) try {
                        count = Math.min(20, Math.max(2, Integer.parseInt(part.substring(6))));
                    } catch (NumberFormatException ignored) {}
                }
                sendJson(exchange, ShieldManifestManager.splitSummon(server, player, count));
            });

            // GET /dismiss-shields?player=X — remove all shields for that player
            httpServer.createContext("/dismiss-shields", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                ShieldManifestManager.dismissAll(server, player);
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /shield-state?player=X — returns {"count":N,"split":bool[,"pool":K],"focus":{...}}
            httpServer.createContext("/shield-state", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ShieldManifestManager.getState(server, player));
            });

            // GET /shield-focus?player=X&mode=stacked|distributed&track=true|false
            // Converges shields to one direction. mode=distributed (default) places them in a curved
            // grid; mode=stacked stacks them all at one point. track=true continuously follows the
            // nearest living entity; track=false (default) locks to the player's current look direction.
            httpServer.createContext("/shield-focus", exchange -> {
                String player = "PrizmoElectric";
                String mode   = "distributed";
                boolean track = false;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if      (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("mode="))   mode   = part.substring(5);
                    else if (part.startsWith("track="))  track  = "true".equals(part.substring(6));
                }
                sendJson(exchange, ShieldManifestManager.focus(server, player, mode, track));
            });

            // GET /shield-unfocus?player=X — return to Fibonacci dome mode
            httpServer.createContext("/shield-unfocus", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                ShieldManifestManager.unfocus(server, player);
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /summon-gauntlet?player=X — spawn Iron Gauntlet as a player ally
            httpServer.createContext("/summon-gauntlet", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, GauntletManager.summon(server, player));
            });

            // GET /dismiss-gauntlet?player=X — discard the summoned gauntlet
            httpServer.createContext("/dismiss-gauntlet", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, GauntletManager.dismiss(server, player));
            });

            // GET /gauntlet-state?player=X — {"active":bool,"hp":float}
            httpServer.createContext("/gauntlet-state", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, GauntletManager.getState(server, player));
            });

            // GET /enchant-toggle?player=X&id=namespace:name — toggle one passive enchant
            // Returns {"active":bool,"id":"..."} — no server thread needed; ConcurrentHashMap is safe.
            httpServer.createContext("/enchant-toggle", exchange -> {
                String player = "PrizmoElectric", id = null;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("id="))     id     = part.substring(3);
                }
                if (id == null) { sendJson(exchange, "{\"error\":\"missing id\"}"); return; }
                Identifier enchId = Identifier.tryParse(id);
                if (enchId == null) { sendJson(exchange, "{\"error\":\"invalid id\"}"); return; }
                var spe = server.getPlayerManager().getPlayer(player);
                if (spe == null) { sendJson(exchange, "{\"error\":\"player not found\"}"); return; }
                boolean nowActive = EnchantTracker.toggle(spe.getUuid(), enchId);
                sendJson(exchange, "{\"active\":" + nowActive + ",\"id\":\"" + enchId + "\"}");
            });

            // GET /enchant-list?player=X — returns {"active":["id1","id2",...]}
            httpServer.createContext("/enchant-list", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                var spe = server.getPlayerManager().getPlayer(player);
                var active = spe != null ? EnchantTracker.getActiveSet(spe.getUuid()) : Set.of();
                StringBuilder sb = new StringBuilder("{\"active\":[");
                boolean first = true;
                for (var eid : active) {
                    if (!first) sb.append(',');
                    sb.append('"').append(eid).append('"');
                    first = false;
                }
                sb.append("]}");
                sendJson(exchange, sb.toString());
            });

            // GET /enchant-clear?player=X — remove all passive enchantments
            httpServer.createContext("/enchant-clear", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                var spe = server.getPlayerManager().getPlayer(player);
                if (spe != null) EnchantTracker.clearPlayer(spe.getUuid());
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /effect-toggle?player=X&id=namespace:name — toggle one passive status effect
            httpServer.createContext("/effect-toggle", exchange -> {
                String player = "PrizmoElectric", id = null;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("id="))     id     = part.substring(3);
                }
                if (id == null) { sendJson(exchange, "{\"error\":\"missing id\"}"); return; }
                Identifier effId = Identifier.tryParse(id);
                if (effId == null) { sendJson(exchange, "{\"error\":\"invalid id\"}"); return; }
                var spe = server.getPlayerManager().getPlayer(player);
                if (spe == null) { sendJson(exchange, "{\"error\":\"player not found\"}"); return; }
                boolean nowActive = EffectTracker.toggle(spe.getUuid(), effId);
                sendJson(exchange, "{\"active\":" + nowActive + ",\"id\":\"" + effId + "\"}");
            });

            // GET /effect-list?player=X — returns {"active":["id1","id2",...]}
            httpServer.createContext("/effect-list", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                var spe = server.getPlayerManager().getPlayer(player);
                var activeEffects = spe != null ? EffectTracker.getActiveSet(spe.getUuid()) : Set.of();
                StringBuilder sb = new StringBuilder("{\"active\":[");
                boolean first = true;
                for (var eid : activeEffects) {
                    if (!first) sb.append(',');
                    sb.append('"').append(eid).append('"');
                    first = false;
                }
                sb.append("]}");
                sendJson(exchange, sb.toString());
            });

            // GET /effect-clear?player=X — remove all passive status effects
            httpServer.createContext("/effect-clear", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                var spe = server.getPlayerManager().getPlayer(player);
                if (spe != null) EffectTracker.clearPlayer(spe.getUuid());
                sendJson(exchange, "{\"ok\":true}");
            });

            // GET /body-size?player=X&scale=Y — set player size via Pehkui BASE scale (0.05–10.0)
            httpServer.createContext("/body-size", exchange -> {
                String player = "PrizmoElectric";
                float scale = 1.0f;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if      (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("scale="))  { try { scale = Float.parseFloat(part.substring(6)); } catch (NumberFormatException ignored) {} }
                }
                sendJson(exchange, PlayerBodyManager.setSize(server, player, scale));
            });

            // GET /body-reach?player=X&scale=Y — set reach multiplier via Pehkui REACH scale (0.1–32.0)
            httpServer.createContext("/body-reach", exchange -> {
                String player = "PrizmoElectric";
                float scale = 1.0f;
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&")) {
                    if      (part.startsWith("player=")) player = part.substring(7);
                    else if (part.startsWith("scale="))  { try { scale = Float.parseFloat(part.substring(6)); } catch (NumberFormatException ignored) {} }
                }
                sendJson(exchange, PlayerBodyManager.setReach(server, player, scale));
            });

            // GET /body-reset?player=X — restore Pehkui BASE and REACH to default (1.0)
            httpServer.createContext("/body-reset", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, PlayerBodyManager.reset(server, player));
            });

            // GET /arrow-manifest?player=X — conjure one floating arrow (costs inventory arrow or exhaustion)
            httpServer.createContext("/arrow-manifest", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ArrowManifestManager.manifest(server, player));
            });

            // GET /arrow-shoot?player=X — launch all manifested arrows at the player's look direction
            httpServer.createContext("/arrow-shoot", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ArrowManifestManager.shoot(server, player));
            });

            // GET /arrow-dismiss?player=X — discard all manifested arrows without shooting
            httpServer.createContext("/arrow-dismiss", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ArrowManifestManager.dismiss(server, player));
            });

            // GET /arrow-state?player=X — {"count":N}
            httpServer.createContext("/arrow-state", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, ArrowManifestManager.getState(server, player));
            });

            // GET /summon-clone?player=X — spawn one flying clone with the summoner's skin
            httpServer.createContext("/summon-clone", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, CloneManager.summon(server, player));
            });

            // GET /dismiss-clone?player=X — remove the clone for that player
            httpServer.createContext("/dismiss-clone", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, CloneManager.dismiss(server, player));
            });

            // GET /clone-state?player=X — {"active":bool,"target":"name"|null}
            httpServer.createContext("/clone-state", exchange -> {
                String player = "PrizmoElectric";
                String q = exchange.getRequestURI().getQuery();
                if (q != null) for (String part : q.split("&"))
                    if (part.startsWith("player=")) player = part.substring(7);
                sendJson(exchange, CloneManager.getState(server, player));
            });

            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Commands starting with "/" execute immediately as the server console
    // (permission level 4) — e.g. "/give PrizmoElectric diamond 1", "/tp ...",
    // "/setblock ...", "/weather clear", "/summon ...". Anything else is queued
    // for the named bot's chat-command parser via /terminal-command-state.
    private static String runTerminalCommand(String cmd, String player) {
        if (cmd.isEmpty()) return "{\"error\":\"empty command\"}";
        if (cmd.startsWith("/")) {
            String vanilla = cmd.substring(1);
            CompletableFuture<String> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    server.getCommandManager().executeWithPrefix(server.getCommandSource(), vanilla);
                    future.complete("{\"ok\":true,\"executed\":\"" + escapeJson(vanilla) + "\"}");
                } catch (Exception e) {
                    future.complete("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}");
                }
            });
            try { return future.get(3, TimeUnit.SECONDS); }
            catch (Exception e) { return "{\"error\":\"timeout\"}"; }
        }
        terminalCommands.put(player, new TerminalCommand(cmd, System.currentTimeMillis()));
        return "{\"queued\":\"" + escapeJson(player) + "\"}";
    }

    private static String escapeJson(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String queryParam(com.sun.net.httpserver.HttpExchange exchange, String key, String def) {
        String q = exchange.getRequestURI().getQuery();
        if (q == null) return def;
        for (String part : q.split("&")) {
            if (part.startsWith(key + "=")) return part.substring(key.length() + 1);
        }
        return def;
    }

    /** Runs fn on the server thread with the named player's ServerPlayerEntity, blocking up to 3s. */
    private static String withPlayer(String playerName, java.util.function.Function<ServerPlayerEntity, String> fn) {
        return withPlayer(playerName, fn, null);
    }

    private static String withPlayer(String playerName, java.util.function.Function<ServerPlayerEntity, String> fn, String notFoundResult) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) {
            return notFoundResult != null ? notFoundResult : "{\"error\":\"player '" + playerName + "' not found\"}";
        }
        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try { future.complete(fn.apply(player)); }
            catch (Exception e) { future.complete("{\"error\":\"" + escapeJson(e.getMessage()) + "\"}"); }
        });
        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }

    private static void sendJson(com.sun.net.httpserver.HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String buildAllBlocks() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        int total = Block.STATE_IDS.size();
        for (int sid = 0; sid < total; sid++) {
            BlockState state = Block.STATE_IDS.get(sid);
            if (state == null) continue;
            String name = Registries.BLOCK.getId(state.getBlock()).toString();
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(sid).append("\":\"").append(name).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildAllItems() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Item item : Registries.ITEM) {
            int rawId = Registries.ITEM.getRawId(item);
            String name = Registries.ITEM.getId(item).toString();
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(rawId).append("\":\"").append(name).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    // Returns {"0":{"name":"minecraft:area_effect_cloud","group":"misc","hostile":false},...}
    // "group" is Mojang's own SpawnGroup (monster/creature/ambient/water_creature/
    // water_ambient/underground_water_creature/axolotls/misc) — ground truth for any
    // entity, vanilla or modded. "hostile" is a convenience bool: group == MONSTER.
    private static String buildAllEntities() {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (EntityType<?> type : Registries.ENTITY_TYPE) {
            int rawId = Registries.ENTITY_TYPE.getRawId(type);
            String name = Registries.ENTITY_TYPE.getId(type).toString();
            SpawnGroup group = type.getSpawnGroup();
            String groupName = group.getName();
            boolean hostile = group == SpawnGroup.MONSTER;
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(rawId).append("\":{")
              .append("\"name\":\"").append(name).append("\",")
              .append("\"group\":\"").append(groupName).append("\",")
              .append("\"hostile\":").append(hostile)
              .append("}");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildBlockNames(String query) {
        if (query == null || !query.startsWith("sids=")) return "{}";
        String[] parts = query.substring(5).split(",");
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int sid;
            try { sid = Integer.parseInt(part); } catch (NumberFormatException e) { continue; }
            BlockState state = Block.STATE_IDS.get(sid);
            if (state == null) continue;
            String name = Registries.BLOCK.getId(state.getBlock()).toString();
            if (!first) sb.append(",");
            first = false;
            sb.append("\"").append(sid).append("\":\"").append(name).append("\"");
        }
        sb.append("}");
        return sb.toString();
    }

    private static String buildItemMove(String fromName, int fromSlot, String toName, int toSlot, int count) {
        ServerPlayerEntity from = server.getPlayerManager().getPlayer(fromName);
        ServerPlayerEntity to   = server.getPlayerManager().getPlayer(toName);
        if (from == null) return "{\"error\":\"player '" + fromName + "' not found\"}";
        if (to   == null) return "{\"error\":\"player '" + toName   + "' not found\"}";

        PlayerInventory fromInv = from.getInventory();
        PlayerInventory toInv   = to.getInventory();

        ItemStack peek = fromInv.getStack(fromSlot);
        if (peek.isEmpty()) return "{\"error\":\"slot " + fromSlot + " is empty\"}";

        int actualCount = Math.min(count, peek.getCount());
        String itemId = Registries.ITEM.getId(peek.getItem()).toString();

        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ItemStack current = fromInv.getStack(fromSlot);
                if (current.isEmpty()) { future.complete("{\"error\":\"slot emptied by concurrent op\"}"); return; }
                int take = Math.min(actualCount, current.getCount());
                ItemStack removed = fromInv.removeStack(fromSlot, take);
                if (removed.isEmpty()) { future.complete("{\"error\":\"removeStack returned empty\"}"); return; }

                ItemStack dest = toInv.getStack(toSlot);
                if (dest.isEmpty()) {
                    toInv.setStack(toSlot, removed);
                } else if (ItemStack.canCombine(dest, removed)) {
                    int space = dest.getMaxCount() - dest.getCount();
                    int add   = Math.min(space, removed.getCount());
                    dest.increment(add);
                    removed.decrement(add);
                    toInv.setStack(toSlot, dest);
                    if (!removed.isEmpty()) fromInv.setStack(fromSlot, removed); // overflow back
                } else {
                    // Swap
                    fromInv.setStack(fromSlot, dest);
                    toInv.setStack(toSlot, removed);
                }

                fromInv.markDirty();
                toInv.markDirty();
                future.complete("{\"success\":true,\"item\":\"" + itemId + "\",\"count\":" + take + "}");
            } catch (Exception e) {
                future.complete("{\"error\":\"" + e.getMessage().replace("\"","'") + "\"}");
            }
        });

        try { return future.get(3, TimeUnit.SECONDS); }
        catch (Exception e) { return "{\"error\":\"timeout\"}"; }
    }

    private static String buildItemTransfer(String fromName, String toName, int slot, int count) {
        ServerPlayerEntity from = server.getPlayerManager().getPlayer(fromName);
        ServerPlayerEntity to   = server.getPlayerManager().getPlayer(toName);
        if (from == null) return "{\"error\":\"player '" + fromName + "' not found\"}";
        if (to   == null) return "{\"error\":\"player '" + toName + "' not found\"}";

        PlayerInventory fromInv = from.getInventory();
        ItemStack peek = fromInv.getStack(slot);
        if (peek.isEmpty()) return "{\"error\":\"slot " + slot + " is empty\"}";

        int actualCount = Math.min(count, peek.getCount());
        String itemId = Registries.ITEM.getId(peek.getItem()).toString();

        CompletableFuture<String> future = new CompletableFuture<>();
        server.execute(() -> {
            try {
                ItemStack removed = fromInv.removeStack(slot, actualCount);
                to.giveItemStack(removed);
                fromInv.markDirty();
                future.complete("{\"success\":true,\"item\":\"" + itemId + "\",\"count\":" + actualCount + "}");
            } catch (Exception e) {
                future.complete("{\"error\":\"" + e.getMessage() + "\"}");
            }
        });

        try {
            return future.get(3, TimeUnit.SECONDS);
        } catch (Exception e) {
            return "{\"error\":\"server thread timeout\"}";
        }
    }

    private static String buildBotInventory(String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"error\":\"player not found\",\"inventory\":[]}";
        PlayerInventory inv = player.getInventory();
        StringBuilder sb = new StringBuilder("{\"player\":\"");
        sb.append(playerName).append("\",\"inventory\":[");
        boolean first = true;
        for (int i = 0; i < inv.size(); i++) {
            ItemStack stack = inv.getStack(i);
            if (stack.isEmpty()) continue;
            String id = Registries.ITEM.getId(stack.getItem()).toString();
            String displayName = stack.getName().getString().replace("\\", "\\\\").replace("\"", "\\\"");
            if (!first) sb.append(",");
            first = false;
            sb.append("{\"slot\":").append(i)
              .append(",\"id\":\"").append(id).append("\"")
              .append(",\"count\":").append(stack.getCount())
              .append(",\"name\":\"").append(displayName).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String buildBotState(String playerName) {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(playerName);
        if (player == null) return "{\"connected\":false,\"error\":\"not found\"}";
        float health = player.getHealth();
        int   food   = player.getHungerManager().getFoodLevel();
        String mode  = botModes.getOrDefault(playerName, "idle");
        return "{\"connected\":true,\"health\":" + health
             + ",\"food\":" + food
             + ",\"behaviorMode\":\"" + mode + "\"}";
    }

    private String buildContext() {
        ServerPlayerEntity player = server.getPlayerManager().getPlayer("Zote");
        if (player == null) return "{\"error\":\"player not found\"}";
        ServerWorld world = player.getServerWorld();
        BlockPos feet = player.getBlockPos();
        String biome = world.getBiome(feet).getKey()
            .map(k -> k.getValue().toString()).orElse("unknown");
        StringBuilder sb = new StringBuilder();
        sb.append("{\"position\":{\"x\":").append(player.getX())
          .append(",\"y\":").append(player.getY())
          .append(",\"z\":").append(player.getZ()).append("},")
          .append("\"biome\":\"").append(biome).append("\",")
          .append("\"blocks\":[");
        boolean first = true;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                BlockPos pos = feet.add(dx, 0, dz);
                BlockState state = world.getBlockState(pos);
                String id = Registries.BLOCK.getId(state.getBlock()).toString();
                if (!first) sb.append(",");
                first = false;
                sb.append("{\"x\":").append(pos.getX()).append(",\"z\":").append(pos.getZ())
                  .append(",\"block\":\"").append(id).append("\",\"properties\":{");
                boolean fp = true;
                for (Property<?> p : state.getProperties()) {
                    if (!fp) sb.append(",");
                    fp = false;
                    sb.append("\"").append(p.getName()).append("\":\"")
                      .append(state.get(p)).append("\"");
                }
                sb.append("}}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }
}
