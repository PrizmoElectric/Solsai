package com.zote.contextmod;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;

/**
 * A stub ClientConnection backed by an EmbeddedChannel.
 * All outbound packets (sent to the fake player) are silently discarded.
 * The EmbeddedChannel makes this.channel non-null so the rest of the
 * server code doesn't NPE when it reads connection state.
 */
public class FakeClientConnection extends ClientConnection {

    private FakeClientConnection() {
        super(NetworkSide.SERVERBOUND);
    }

    public static FakeClientConnection create() {
        FakeClientConnection conn = new FakeClientConnection();
        // EmbeddedChannel activates the connection (calls channelActive → sets this.channel).
        // ChannelOutboundHandlerAdapter positioned before 'conn' in outbound traversal order
        // catches every channel.writeAndFlush() and discards the message with no accumulation.
        new EmbeddedChannel(
            new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    ReferenceCountUtil.release(msg);
                    promise.setSuccess();
                }
            },
            conn
        );
        return conn;
    }

    // Belt-and-suspenders: prevent channel.writeAndFlush() from even being reached.
    @Override public void send(Packet<?> packet) {}
    @Override public void send(Packet<?> packet, PacketCallbacks callbacks) {}

    @Override public boolean isOpen() { return true; }
}
