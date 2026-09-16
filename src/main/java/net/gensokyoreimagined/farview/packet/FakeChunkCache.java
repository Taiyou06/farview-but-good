package net.gensokyoreimagined.farview.packet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.gensokyoreimagined.farview.region.SavedChunk;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

public final class FakeChunkCache {
    private record Key(ResourceKey<Level> dimension, long chunkKey) {}

    private final Cache<Key, ClientboundLevelChunkWithLightPacket> cache;

    public FakeChunkCache(long maxBytes, long expireSeconds) {
        this.cache = maxBytes <= 0 ? null : Caffeine.newBuilder()
            .maximumWeight(maxBytes)
            .weigher((Key key, ClientboundLevelChunkWithLightPacket packet) -> weigh(packet))
            .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
            .build();
    }

    public ClientboundLevelChunkWithLightPacket get(ResourceKey<Level> dimension, long chunkKey,
                                                    LongFunction<ClientboundLevelChunkWithLightPacket> loader) {
        if (cache == null) return loader.apply(chunkKey);
        return cache.get(new Key(dimension, chunkKey), key -> loader.apply(key.chunkKey()));
    }

    public void invalidateAll() {
        if (cache != null) cache.invalidateAll();
    }

    private static int weigh(ClientboundLevelChunkWithLightPacket packet) {
        FriendlyByteBuf buffer = packet.getChunkData().getReadBuffer();
        ClientboundLightUpdatePacketData light = packet.getLightData();
        return buffer.readableBytes()
            + (light.getSkyUpdates().size() + light.getBlockUpdates().size()) * SavedChunk.DATA_LAYER_BYTES;
    }
}
