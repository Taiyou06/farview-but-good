package net.gensokyoreimagined.farview;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

final class FarViewWireMeter extends ChannelOutboundHandlerAdapter {
    public static final String NAME = "gensou_farview_wire";

    private final FarViewSession session;

    FarViewWireMeter(FarViewSession session) {
        this.session = session;
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ByteBuf buf) {
            session.quality().onWireBytes(buf.readableBytes());
        }
        super.write(ctx, msg, promise);
    }
}
