package net.gensokyoreimagined.farview.packet;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.gensokyoreimagined.farview.nms.FakeChunk;
import org.bukkit.NamespacedKey;

import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

public final class FakeChunkCache {
    private record Key(NamespacedKey dimension, long chunkKey) {}

    private final Cache<Key, FakeChunk> cache;

    public FakeChunkCache(long maxBytes, long expireSeconds) {
        this.cache = maxBytes <= 0 ? null : Caffeine.newBuilder()
            .maximumWeight(maxBytes)
            .weigher((Key key, FakeChunk chunk) -> chunk.bytes())
            .expireAfterWrite(expireSeconds, TimeUnit.SECONDS)
            .build();
    }

    public FakeChunk get(NamespacedKey dimension, long chunkKey, LongFunction<FakeChunk> loader) {
        if (cache == null) return loader.apply(chunkKey);
        return cache.get(new Key(dimension, chunkKey), key -> loader.apply(key.chunkKey()));
    }

    public void invalidateAll() {
        if (cache != null) cache.invalidateAll();
    }
}
