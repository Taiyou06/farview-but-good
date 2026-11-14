package net.gensokyoreimagined.farview.nms.v26_3;

import io.papermc.paper.configuration.WorldConfiguration;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.gensokyoreimagined.farview.nms.BlockEntityPolicy;
import net.gensokyoreimagined.farview.nms.ChunkSource;
import net.gensokyoreimagined.farview.nms.PreparedChunk;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.bukkit.World;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;

final class WorldRegionSource implements ChunkSource {
    private final Path regionDir;
    private final int minSectionY;
    private final int maxSectionY;
    private final boolean hasSkyLight;
    private final PalettedContainerFactory containerFactory;
    private final RegistryAccess registryAccess;
    private final HolderGetter<Biome> biomeLookup;
    private final Consumer<String> skipLog;
    private final Set<Block> hiddenBlocks;
    private final int hiddenBelowSectionY;
    private final World.Environment environment;

    private final int readerCacheSize;
    private final Long2ObjectLinkedOpenHashMap<RegionReader> readers = new Long2ObjectLinkedOpenHashMap<>();

    WorldRegionSource(ServerLevel level, MinecraftServer server, int readerCacheSize, Consumer<String> skipLog) {
        this.regionDir = server.storageSource.getDimensionPath(level.dimension()).resolve("region");
        this.minSectionY = level.getMinSectionY();
        this.maxSectionY = level.getMaxSectionY();
        this.hasSkyLight = level.dimensionType().hasSkyLight();
        this.containerFactory = level.palettedContainerFactory();
        this.registryAccess = level.registryAccess();
        this.biomeLookup = this.registryAccess.lookupOrThrow(Registries.BIOME);
        this.readerCacheSize = Math.max(1, readerCacheSize);
        this.skipLog = skipLog;
        WorldConfiguration.Anticheat.AntiXray antiXray = level.paperConfig().anticheat.antiXray;
        this.hiddenBlocks = antiXray.enabled ? new ReferenceOpenHashSet<>(antiXray.hiddenBlocks) : Set.of();
        this.hiddenBelowSectionY = antiXray.maxBlockHeight >> 4;
        this.environment = level.getWorld().getEnvironment();
    }

    @Override
    public int minSectionY() { return minSectionY; }
    int maxSectionY() { return maxSectionY; }
    @Override
    public int sectionCount() { return maxSectionY - minSectionY + 1; }
    boolean hasSkyLight() { return hasSkyLight; }
    PalettedContainerFactory containerFactory() { return containerFactory; }
    RegistryAccess registryAccess() { return registryAccess; }

    @Override
    public PreparedChunk read(int chunkX, int chunkZ, BlockEntityPolicy blockEntities, boolean requireSavedLight,
                              boolean cullSections) throws IOException {
        SavedChunk saved = readChunk(chunkX, chunkZ, blockEntities);
        if (saved == null) return skip(chunkX, chunkZ, "not saved to disk");
        if (!saved.full()) return skip(chunkX, chunkZ, "status is not full");
        if (requireSavedLight && !saved.hasValidLight()) {
            return skip(chunkX, chunkZ, "saved light is missing or stale");
        }
        if (!hiddenBlocks.isEmpty()) hideAntiXrayBlocks(saved);
        return FakeChunkPacketFactory.prepare(this, chunkX, chunkZ, saved, cullSections);
    }

    private void hideAntiXrayBlocks(SavedChunk saved) {
        BlockState[] palette = saved.blockPalette();
        for (int y = minSectionY; y <= maxSectionY && y < hiddenBelowSectionY; y++) {
            SavedChunk.Section section = saved.section(y);
            if (section == null) continue;
            BlockState stone = switch (environment) {
                case NETHER -> Blocks.NETHERRACK.defaultBlockState();
                case THE_END -> Blocks.END_STONE.defaultBlockState();
                default -> y < 0 ? Blocks.DEEPSLATE.defaultBlockState() : Blocks.STONE.defaultBlockState();
            };
            int end = section.blockPaletteFrom() + section.blockPaletteCount();
            for (int i = section.blockPaletteFrom(); i < end; i++) {
                if (hiddenBlocks.contains(palette[i].getBlock())) palette[i] = stone;
            }
        }
    }

    @Override
    public Object packet(PreparedChunk prepared, long[] realSections) {
        return FakeChunkPacketFactory.packet(this, prepared, realSections);
    }

    private PreparedChunk skip(int chunkX, int chunkZ, String reason) {
        if (skipLog != null) skipLog.accept("skipped chunk " + chunkX + "," + chunkZ + ": " + reason);
        return null;
    }

    private SavedChunk readChunk(int chunkX, int chunkZ, BlockEntityPolicy blockEntities) throws IOException {
        RegionReader reader = reader(chunkX >> 5, chunkZ >> 5);
        if (reader == null) return null;
        SavedChunkReader into = SavedChunkReader.forThread();
        into.begin(biomeLookup, containerFactory.defaultBlockState(), containerFactory.defaultBiome(),
            minSectionY, maxSectionY, blockEntities);
        return reader.read(chunkX, chunkZ, into);
    }

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
