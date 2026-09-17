package net.gensokyoreimagined.farview.nms;

import io.netty.channel.ChannelHandlerContext;
import org.bukkit.NamespacedKey;

public interface SessionHooks {
    void attach(ChannelHandlerContext ctx);

    void onCenter(int chunkX, int chunkZ);

    void onServerChunk(int chunkX, int chunkZ);

    boolean onForget(int chunkX, int chunkZ);

    void onServerRadius(int radius);

    int effectiveRadius();

    void onRespawn(NamespacedKey dimension);

    void onKeepAliveSent(long id);

    void onKeepAliveResponse(long id);
}
