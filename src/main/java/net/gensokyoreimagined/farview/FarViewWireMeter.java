package net.gensokyoreimagined.farview;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

import java.util.concurrent.atomic.AtomicLong;

final class FarViewWireMeter extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "gensou_farview_wire";

    private final FarViewSession session;
    private final AtomicLong globalTokens;

    FarViewWireMeter(FarViewSession session, AtomicLong globalTokens) {
        this.session = session;
        this.globalTokens = globalTokens;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            session.quality().onWireBytes(buf.readableBytes());
            globalTokens.addAndGet(-buf.readableBytes());
        }
        super.write(ctx, msg, promise);
    }
}
