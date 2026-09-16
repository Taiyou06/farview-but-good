package net.gensokyoreimagined.farview;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

final class FarViewInjector {
    private FarViewInjector() {}

    static FarViewSession inject(FarViewPlugin plugin, Player player) {
        ServerPlayer handle = ((CraftPlayer) player).getHandle();
        ChannelPipeline pipeline = handle.connection.connection.channel.pipeline();
        uninject(player);

        FarViewSession session = new FarViewSession(
            player, pipeline.channel(), plugin,
            handle.level().dimension(), player.getSendViewDistance(),
            plugin.settings().rate());

        pipeline.addLast(FarViewChannelHandler.NAME, new FarViewChannelHandler(session));
        pipeline.addFirst(FarViewWireMeter.NAME, new FarViewWireMeter(session));
        ChannelHandlerContext connection = pipeline.context(Connection.class);
        if (connection != null) {
            pipeline.addBefore(connection.name(), FarViewInboundHandler.NAME, new FarViewInboundHandler(session));
        }
        return session;
    }

    static void uninject(Player player) {
        ChannelPipeline pipeline = ((CraftPlayer) player).getHandle().connection.connection.channel.pipeline();
        for (String name : new String[] { FarViewChannelHandler.NAME, FarViewInboundHandler.NAME, FarViewWireMeter.NAME }) {
            if (pipeline.context(name) != null) pipeline.remove(name);
        }
    }
}
