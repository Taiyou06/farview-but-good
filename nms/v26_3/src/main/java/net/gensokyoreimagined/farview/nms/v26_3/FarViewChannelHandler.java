package net.gensokyoreimagined.farview.nms.v26_3;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import net.gensokyoreimagined.farview.nms.SessionHooks;
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;

final class FarViewChannelHandler extends ChannelDuplexHandler {
    private final SessionHooks session;

    FarViewChannelHandler(SessionHooks session) {
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
            session.onServerChunk(packet.x(), packet.z());

        } else if (msg instanceof ClientboundForgetLevelChunkPacket packet) {
            if (session.onForget(packet.pos().x(), packet.pos().z())) {
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
            session.onRespawn(CraftNamespacedKey.fromMinecraft(packet.commonPlayerSpawnInfo().dimension().identifier()));

        } else if (msg instanceof ClientboundKeepAlivePacket packet) {
            session.onKeepAliveSent(packet.getId());
        }

        super.write(ctx, msg, promise);
    }
}
