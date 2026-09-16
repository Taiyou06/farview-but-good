package net.gensokyoreimagined.farview;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;

/** Packets written from this handler's own context skip {@link #write}. */
final class FarViewChannelHandler extends ChannelDuplexHandler {

    public static final String NAME = "gensou_farview";

    private final FarViewSession session;

    FarViewChannelHandler(FarViewSession session) {
        this.session = session;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) {
        session.attach(ctx);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof ClientboundSetChunkCacheCenterPacket packet) {
            session.onCenter(packet.getX(), packet.getZ());

        } else if (msg instanceof ClientboundLevelChunkWithLightPacket packet) {
            session.onServerChunk(packet.getX(), packet.getZ());

        } else if (msg instanceof ClientboundForgetLevelChunkPacket packet) {
            if (session.onForget(packet.pos())) {
                ReferenceCountUtil.release(msg);
                promise.trySuccess();
                return;
            }

        } else if (msg instanceof ClientboundSetChunkCacheRadiusPacket packet) {
            session.onServerRadius(packet.getRadius());
            int effective = session.effectiveRadius();
            if (effective != packet.getRadius()) {
                ReferenceCountUtil.release(msg);
                super.write(ctx, new ClientboundSetChunkCacheRadiusPacket(effective), promise);
                return;
            }

        } else if (msg instanceof ClientboundRespawnPacket packet) {
            session.onRespawn(packet.commonPlayerSpawnInfo().dimension());

        } else if (msg instanceof ClientboundKeepAlivePacket packet) {
            session.onKeepAliveSent(packet.getId());
        }

        super.write(ctx, msg, promise);
    }
}
