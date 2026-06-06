package com.zote.contextmod;

import com.sun.net.httpserver.HttpServer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;

import java.util.concurrent.ConcurrentLinkedQueue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

public class ContextMod implements ModInitializer {

    // Player whose C2S packets are captured for mirror/recording
    public static final String MIRROR_PLAYER = "PrizmoElectric";

    // Thread-safe buffer drained by GET /mirror-events
    static final ConcurrentLinkedQueue<String> mirrorBuffer = new ConcurrentLinkedQueue<>();
    private static final int MAX_BUFFER = 2000;

    public static void addMirrorEvent(String json) {
        if (mirrorBuffer.size() < MAX_BUFFER) mirrorBuffer.add(json);
    }

    private static MinecraftServer server;
    private HttpServer httpServer;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(s -> {
            server = s;
            startHttpServer();
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(s -> {
            if (httpServer != null) httpServer.stop(0);
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

            httpServer.start();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
