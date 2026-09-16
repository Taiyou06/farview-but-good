package net.gensokyoreimagined.farview;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

final class FarViewInboundHandler extends ChannelInboundHandlerAdapter {

    public static final String NAME = "gensou_farview_in";

    private final FarViewSession session;

    FarViewInboundHandler(FarViewSession session) {
        this.session = session;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        if (msg instanceof ServerboundKeepAlivePacket packet) {
            session.onKeepAliveResponse(packet.getId());
        }
        super.channelRead(ctx, msg);
    }
}
