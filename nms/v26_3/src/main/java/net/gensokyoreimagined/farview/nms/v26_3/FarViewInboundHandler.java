package net.gensokyoreimagined.farview.nms.v26_3;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.gensokyoreimagined.farview.nms.SessionHooks;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;

final class FarViewInboundHandler extends ChannelInboundHandlerAdapter {
    private final SessionHooks session;

    FarViewInboundHandler(SessionHooks session) {
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
