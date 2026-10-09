package net.gensokyoreimagined.farview.packet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.gensokyoreimagined.farview.nms.PreparedChunk;
import org.bukkit.NamespacedKey;

import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

public final class FakeChunkCache {
    private record Key(NamespacedKey dimension, long chunkKey) {}

    private final Cache<Key, PreparedChunk> cache;

    public FakeChunkCache(long maxBytes, long expireSeconds) {
        this.cache = maxBytes <= 0 ? null : Caffeine.newBuilder()
            .maximumWeight(maxBytes)
            .weigher((Key key, PreparedChunk chunk) -> chunk.weight())
            .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
            .build();
    }

    public PreparedChunk get(NamespacedKey dimension, long chunkKey, LongFunction<PreparedChunk> loader) {
        if (cache == null) return loader.apply(chunkKey);
        return cache.get(new Key(dimension, chunkKey), key -> loader.apply(key.chunkKey()));
    }

    public void invalidateAll() {
        if (cache != null) cache.invalidateAll();
    }
}
