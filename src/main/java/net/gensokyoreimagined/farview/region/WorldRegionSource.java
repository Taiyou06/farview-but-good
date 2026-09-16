package net.gensokyoreimagined.farview.region;

import net.gensokyoreimagined.farview.FarViewSettings;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.PalettedContainerFactory;

import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.Path;

public final class WorldRegionSource implements AutoCloseable {

    private final ResourceKey<Level> dimension;
    private final Path regionDir;
    private final int minSectionY;
    private final int maxSectionY;
    private final boolean hasSkyLight;
    private final PalettedContainerFactory containerFactory;
    private final RegistryAccess registryAccess;
    private final HolderGetter<Biome> biomeLookup;

    private final int readerCacheSize;
    private final Long2ObjectLinkedOpenHashMap<RegionReader> readers = new Long2ObjectLinkedOpenHashMap<>();

    public WorldRegionSource(ServerLevel level, MinecraftServer server, int readerCacheSize) {
        this.dimension = level.dimension();
        this.regionDir = server.storageSource.getDimensionPath(this.dimension).resolve("region");
        this.minSectionY = level.getMinSectionY();
        this.maxSectionY = level.getMaxSectionY();
        this.hasSkyLight = level.dimensionType().hasSkyLight();
        this.containerFactory = level.palettedContainerFactory();
        this.registryAccess = level.registryAccess();
        this.biomeLookup = this.registryAccess.lookupOrThrow(Registries.BIOME);
        this.readerCacheSize = Math.max(1, readerCacheSize);
    }

    public ResourceKey<Level> dimension() { return dimension; }
    public int minSectionY() { return minSectionY; }
    public int maxSectionY() { return maxSectionY; }
    public boolean hasSkyLight() { return hasSkyLight; }
    public PalettedContainerFactory containerFactory() { return containerFactory; }
    public RegistryAccess registryAccess() { return registryAccess; }

    /** Null when never generated or saved. The result is overwritten by this thread's next read. */
    public SavedChunk readChunk(int chunkX, int chunkZ, FarViewSettings.BlockEntityPolicy blockEntities)
        throws IOException {
        RegionReader reader = reader(chunkX >> 5, chunkZ >> 5);
        if (reader == null) return null;
        SavedChunkReader into = SavedChunkReader.forThread();
        into.begin(biomeLookup, containerFactory.defaultBlockState(), containerFactory.defaultBiome(),
            minSectionY, maxSectionY, blockEntities);
        return reader.read(chunkX, chunkZ, into);
    }

    // FileChannel positional reads are thread-safe, so only the cache itself needs guarding.
    private synchronized RegionReader reader(int regionX, int regionZ) throws IOException {
        long key = ChunkPos.pack(regionX, regionZ);
        RegionReader cached = readers.getAndMoveToLast(key);
        if (cached != null) return cached;

        RegionReader opened = RegionReader.open(regionDir, regionX, regionZ);
        if (opened == null) return null;

        readers.putAndMoveToLast(key, opened);
        if (readers.size() > readerCacheSize) {
            try { readers.removeFirst().close(); } catch (IOException ignored) {}
        }
        return opened;
    }

    @Override
    public synchronized void close() {
        for (RegionReader reader : readers.values()) {
            try { reader.close(); } catch (IOException ignored) {}
        }
        readers.clear();
    }
}
