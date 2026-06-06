package com.zote.contextmod.mixin;

import com.zote.contextmod.ContextMod;
import net.minecraft.network.packet.c2s.play.*;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.hit.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerPlayNetworkHandler.class)
public class MirrorCaptureMixin {

    @Shadow public ServerPlayerEntity player;

    private boolean isMaster() {
        return player != null && ContextMod.MIRROR_PLAYER.equals(player.getName().getString());
    }

    // ── Movement + look ───────────────────────────────────────────────────────

    @Inject(method = "onPlayerMove", at = @At("HEAD"))
    private void captureMove(PlayerMoveC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        boolean hasPos  = packet.changesPosition();
        boolean hasLook = packet.changesLook();
        if (!hasPos && !hasLook) return;

        StringBuilder sb = new StringBuilder("{\"type\":\"move\"");
        if (hasPos) {
            sb.append(",\"x\":").append(packet.getX(player.getX()));
            sb.append(",\"y\":").append(packet.getY(player.getY()));
            sb.append(",\"z\":").append(packet.getZ(player.getZ()));
        }
        if (hasLook) {
            sb.append(",\"yaw\":").append(packet.getYaw(player.getYaw()));
            sb.append(",\"pitch\":").append(packet.getPitch(player.getPitch()));
        }
        sb.append(",\"onGround\":").append(packet.isOnGround());
        sb.append("}");
        ContextMod.addMirrorEvent(sb.toString());
    }

    // ── Dig start / stop / abort ──────────────────────────────────────────────

    @Inject(method = "onPlayerAction", at = @At("HEAD"))
    private void captureAction(PlayerActionC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        String action = switch (packet.getAction()) {
            case START_DESTROY_BLOCK  -> "dig_start";
            case ABORT_DESTROY_BLOCK  -> "dig_abort";
            case STOP_DESTROY_BLOCK   -> "dig_stop";
            case DROP_ALL_ITEMS       -> "drop_stack";
            case DROP_ITEM            -> "drop_item";
            case RELEASE_USE_ITEM     -> "release_item";
            case SWAP_ITEM_WITH_OFFHAND -> "swap_offhand";
        };
        BlockPos pos = packet.getPos();
        ContextMod.addMirrorEvent(
            "{\"type\":\"action\",\"action\":\"" + action + "\"" +
            ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() + "}"
        );
    }

    // ── Right-click block ─────────────────────────────────────────────────────

    @Inject(method = "onPlayerInteractBlock", at = @At("HEAD"))
    private void captureUseBlock(PlayerInteractBlockC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        BlockHitResult hit  = packet.getBlockHitResult();
        BlockPos pos        = hit.getBlockPos();
        String face         = hit.getSide().getName();
        String hand         = packet.getHand().name().toLowerCase();
        ContextMod.addMirrorEvent(
            "{\"type\":\"use_block\"" +
            ",\"x\":" + pos.getX() + ",\"y\":" + pos.getY() + ",\"z\":" + pos.getZ() +
            ",\"face\":\"" + face + "\",\"hand\":\"" + hand + "\"}"
        );
    }

    // ── Right-click entity ────────────────────────────────────────────────────
    // Note: entity ID is a private field with no Yarn accessor in 1.20.1.
    // We record the event + player position so Nilo can target the nearest entity on replay.

    @Inject(method = "onPlayerInteractEntity", at = @At("HEAD"))
    private void captureUseEntity(PlayerInteractEntityC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        ContextMod.addMirrorEvent(
            "{\"type\":\"use_entity\"" +
            ",\"px\":" + player.getX() + ",\"py\":" + player.getY() + ",\"pz\":" + player.getZ() + "}"
        );
    }

    // ── Right-click with item (air) ───────────────────────────────────────────

    @Inject(method = "onPlayerInteractItem", at = @At("HEAD"))
    private void captureUseItem(PlayerInteractItemC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        String hand = packet.getHand().name().toLowerCase();
        ContextMod.addMirrorEvent("{\"type\":\"use_item\",\"hand\":\"" + hand + "\"}");
    }

    // ── Sneak / sprint ────────────────────────────────────────────────────────

    @Inject(method = "onClientCommand", at = @At("HEAD"), require = 0)
    private void captureCommand(ClientCommandC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        String action = switch (packet.getMode()) {
            case PRESS_SHIFT_KEY   -> "sneak_start";
            case RELEASE_SHIFT_KEY -> "sneak_stop";
            case START_SPRINTING   -> "sprint_start";
            case STOP_SPRINTING    -> "sprint_stop";
            default                -> null;
        };
        if (action == null) return;
        ContextMod.addMirrorEvent("{\"type\":\"command\",\"action\":\"" + action + "\"}");
    }

    // ── Custom mod packets (magic staves, special abilities, etc.) ────────────

    @Inject(method = "onCustomPayload", at = @At("HEAD"), require = 0)
    private void captureCustom(CustomPayloadC2SPacket packet, CallbackInfo ci) {
        if (!isMaster()) return;
        String channel = packet.getChannel().toString();
        // Skip vanilla and Fabric system channels — only capture mod gameplay packets
        if (channel.startsWith("minecraft:") || channel.startsWith("fabric:")) return;

        int len = Math.min(packet.getData().readableBytes(), 512);
        byte[] bytes = new byte[len];
        packet.getData().getBytes(packet.getData().readerIndex(), bytes);
        StringBuilder hex = new StringBuilder();
        for (byte b : bytes) hex.append(String.format("%02x", b));

        ContextMod.addMirrorEvent(
            "{\"type\":\"custom_packet\",\"channel\":\"" + channel + "\",\"data\":\"" + hex + "\"}"
        );
    }
}
