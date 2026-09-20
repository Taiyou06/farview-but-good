package net.gensokyoreimagined.farview.nms;

import io.netty.channel.Channel;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.function.Consumer;

public interface FarViewNms {
    String OUTBOUND_HANDLER = "gensou_farview";
    String INBOUND_HANDLER = "gensou_farview_in";

    Channel channel(Player player);

    void inject(Channel channel, SessionHooks session);

    Object chunkRadiusPacket(int radius);

    Object forgetChunkPacket(int chunkX, int chunkZ);

    ChunkSource openWorld(World world, int readerCacheSize, Consumer<String> skipLog);
}
