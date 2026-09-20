package net.gensokyoreimagined.farview.nms.v26_1;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.gensokyoreimagined.farview.nms.ChunkSource;
import net.gensokyoreimagined.farview.nms.FarViewNms;
import net.gensokyoreimagined.farview.nms.SessionHooks;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheRadiusPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.ChunkPos;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

public final class FarViewNmsImpl implements FarViewNms {
    @Override
    public Channel channel(Player player) {
        return ((CraftPlayer) player).getHandle().connection.connection.channel;
    }

    @Override
    public void inject(Channel channel, SessionHooks session) {
        ChannelPipeline pipeline = channel.pipeline();
        pipeline.addLast(OUTBOUND_HANDLER, new FarViewChannelHandler(session));
        ChannelHandlerContext connection = pipeline.context(Connection.class);
        if (connection != null) {
            pipeline.addBefore(connection.name(), INBOUND_HANDLER, new FarViewInboundHandler(session));
        }
    }

    @Override
    public Object chunkRadiusPacket(int radius) {
        return new ClientboundSetChunkCacheRadiusPacket(radius);
    }

    @Override
    public Object forgetChunkPacket(int chunkX, int chunkZ) {
        return new ClientboundForgetLevelChunkPacket(new ChunkPos(chunkX, chunkZ));
    }

    @Override
    public ChunkSource openWorld(World world, int readerCacheSize, Consumer<String> skipLog) {
        return new WorldRegionSource(((CraftWorld) world).getHandle(), MinecraftServer.getServer(), readerCacheSize, skipLog);
    }
}
