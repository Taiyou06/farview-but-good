package net.gensokyoreimagined.farview.nms.v26_1;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.gensokyoreimagined.farview.nms.FakeChunk;
import net.minecraft.core.IdMap;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.PalettedContainerRO;
import net.minecraft.world.level.chunk.Strategy;
import net.minecraft.world.level.levelgen.Heightmap;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

final class FakeChunkPacketFactory {
    private static final int LIGHT_STATE_NULL = 0;
    private static final int LIGHT_STATE_INIT = 2;
    private static final int LIGHT_STATE_HIDDEN = 3;

    private static final StreamCodec<RegistryFriendlyByteBuf, BlockEntityType<?>> BLOCK_ENTITY_TYPE =
        ByteBufCodecs.registry(Registries.BLOCK_ENTITY_TYPE);

    private static final int MIN_BLOCK_BITS = 4;
    private static final int MAX_LOCAL_BLOCK_BITS = 8;
    private static final int MIN_BIOME_BITS = 1;
    private static final int MAX_LOCAL_BIOME_BITS = 3;

    private static final int SCRATCH_INITIAL_BYTES = 32 * 1024;
    private static final int SCRATCH_MAX_BYTES = 1024 * 1024;

    private static final ThreadLocal<ByteBuf> PACKET_SCRATCH =
        ThreadLocal.withInitial(() -> Unpooled.buffer(SCRATCH_INITIAL_BYTES));
    private static final ThreadLocal<ByteBuf> SECTION_SCRATCH =
        ThreadLocal.withInitial(() -> Unpooled.buffer(SCRATCH_INITIAL_BYTES));

    private static final ThreadLocal<int[]> HISTOGRAM_SCRATCH =
        ThreadLocal.withInitial(() -> new int[1 << MAX_LOCAL_BLOCK_BITS]);

    private static final ThreadLocal<long[]> MASK_SCRATCH = ThreadLocal.withInitial(() -> new long[4]);

    private static final VarHandle LONG_AT =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private FakeChunkPacketFactory() {}

    static FakeChunk build(WorldRegionSource source, int chunkX, int chunkZ, SavedChunk saved) {
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(scratch(PACKET_SCRATCH), source.registryAccess());
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        writeChunkData(out, source, saved);
        writeLightData(out, source, saved);

        ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(out);
        packet.setReady(true);
        return new FakeChunk(packet, weigh(packet));
    }

    private static int weigh(ClientboundLevelChunkWithLightPacket packet) {
        ClientboundLightUpdatePacketData light = packet.getLightData();
        return packet.getChunkData().getReadBuffer().readableBytes()
            + (light.getSkyUpdates().size() + light.getBlockUpdates().size()) * SavedChunk.DATA_LAYER_BYTES;
    }

    private static ByteBuf scratch(ThreadLocal<ByteBuf> holder) {
        ByteBuf buffer = holder.get();
        if (buffer.capacity() > SCRATCH_MAX_BYTES) {
            buffer = Unpooled.buffer(SCRATCH_INITIAL_BYTES);
            holder.set(buffer);
        }
        return buffer.clear();
    }

    private static void writeChunkData(RegistryFriendlyByteBuf out, WorldRegionSource source, SavedChunk saved) {
        writeHeightmaps(out, saved);

        FriendlyByteBuf sectionBuf = new FriendlyByteBuf(scratch(SECTION_SCRATCH));
        for (int y = source.minSectionY(); y <= source.maxSectionY(); y++) {
            writeSection(sectionBuf, source.containerFactory(), saved, saved.section(y));
        }
        out.writeVarInt(sectionBuf.readableBytes());
        out.writeBytes(sectionBuf);

        writeBlockEntities(out, saved);
    }

    private static void writeHeightmaps(RegistryFriendlyByteBuf out, SavedChunk saved) {
        out.writeVarInt(saved.heightmapCount());
        for (int i = 0; i < saved.heightmapCount(); i++) {
            Heightmap.Types.STREAM_CODEC.encode(out, saved.heightmapType(i));
            out.writeVarInt(saved.heightmapWords(i));
            out.writeBytes(saved.bytes(), saved.heightmapAt(i), saved.heightmapWords(i) * 8);
        }
    }

    private static void writeSection(FriendlyByteBuf out, PalettedContainerFactory factory,
                                     SavedChunk saved, SavedChunk.Section section) {
        Strategy<BlockState> blockStrategy = factory.blockStatesStrategy();
        int blockCount = section == null ? -1 : section.blockPaletteCount();
        long counts = blockCount < 0 ? 0L
            : countBlocks(saved.blockPalette(), section.blockPaletteFrom(), blockCount, saved.bytes(),
                section.blockDataAt(), section.blockDataWords(),
                storedBits(blockCount, MIN_BLOCK_BITS), blockStrategy.entryCount());

        out.writeShort((int) (counts >>> 32));
        out.writeShort((int) counts);
        if (section == null) {
            writeAbsent(out, blockStrategy, factory.defaultBlockState());
            writeAbsent(out, factory.biomeStrategy(), factory.defaultBiome());
            return;
        }
        writeContainer(out, blockStrategy, factory.defaultBlockState(),
            saved.blockPalette(), section.blockPaletteFrom(), blockCount,
            saved.bytes(), section.blockDataAt(), section.blockDataWords(),
            MIN_BLOCK_BITS, MAX_LOCAL_BLOCK_BITS);
        writeContainer(out, factory.biomeStrategy(), factory.defaultBiome(),
            saved.biomePalette(), section.biomePaletteFrom(), section.biomePaletteCount(),
            saved.bytes(), section.biomeDataAt(), section.biomeDataWords(),
            MIN_BIOME_BITS, MAX_LOCAL_BIOME_BITS);
    }

    private static <T> void writeContainer(FriendlyByteBuf out, Strategy<T> strategy, T defaultValue,
                                   T[] palette, int from, int count,
                                   byte[] bytes, int dataAt, int dataWords,
                                   int minBits, int maxLocalBits) {
        if (count < 0) {
            writeAbsent(out, strategy, defaultValue);
            return;
        }
        if (count == 0) throw new IllegalArgumentException("saved section holds an empty palette");

        int bits = storedBits(count, minBits);
        if (bits > maxLocalBits) {
            unpack(strategy, palette, from, count, bytes, dataAt, dataWords, defaultValue).write(out, null, 0);
            return;
        }

        IdMap<T> globalMap = strategy.globalMap();
        out.writeByte(bits);
        if (bits == 0) {
            out.writeVarInt(globalMap.getId(palette[from]));
            return;
        }

        checkPacked(dataWords, strategy.entryCount(), bits);
        out.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            out.writeVarInt(globalMap.getId(palette[from + i]));
        }
        out.writeBytes(bytes, dataAt, dataWords * 8);
    }

    private static <T> void writeAbsent(FriendlyByteBuf out, Strategy<T> strategy, T value) {
        out.writeByte(0);
        out.writeVarInt(strategy.globalMap().getId(value));
    }

    private static long countBlocks(BlockState[] palette, int from, int count, byte[] bytes, int dataAt, int dataWords,
                            int bits, int entryCount) {
        if (bits == 0) return tally(palette[from], entryCount, 0L);

        checkPacked(dataWords, entryCount, bits);
        int[] histogram = histogram(1 << bits);
        long mask = (1L << bits) - 1L;
        int valuesPerLong = 64 / bits;
        for (int word = 0, index = 0; index < entryCount; word++, index += valuesPerLong) {
            long cell = (long) LONG_AT.get(bytes, dataAt + word * 8);
            int n = Math.min(valuesPerLong, entryCount - index);
            for (int i = 0; i < n; i++) {
                histogram[(int) (cell & mask)]++;
                cell >>>= bits;
            }
        }

        long counts = 0L;
        for (int id = 0; id < (1 << bits); id++) {
            if (histogram[id] == 0) continue;
            if (id >= count) {
                throw new IllegalArgumentException("saved section indexes palette entry " + id + " of " + count);
            }
            counts = tally(palette[from + id], histogram[id], counts);
        }
        return counts;
    }

    private static long tally(BlockState state, int count, long counts) {
        if (state.isAir()) return counts;
        counts += (long) count << 32;
        if (!state.getFluidState().isEmpty()) counts += count;
        return counts;
    }

    private static int[] histogram(int size) {
        int[] histogram = HISTOGRAM_SCRATCH.get();
        if (histogram.length < size) {
            histogram = new int[size];
            HISTOGRAM_SCRATCH.set(histogram);
        }
        Arrays.fill(histogram, 0, size, 0);
        return histogram;
    }

    private static int storedBits(int paletteSize, int minBits) {
        int bits = Mth.ceillog2(paletteSize);
        return bits == 0 ? 0 : Math.max(bits, minBits);
    }

    private static void checkPacked(int words, int entryCount, int bits) {
        int expected = Math.ceilDiv(entryCount, 64 / bits);
        if (words != expected) {
            throw new IllegalArgumentException("saved section holds " + words + " packed words at "
                + bits + " bits, expected " + expected);
        }
    }

    private static <T> PalettedContainer<T> unpack(Strategy<T> strategy, T[] palette, int from, int count,
                                                   byte[] bytes, int dataAt, int dataWords, T defaultValue) {
        long[] data = new long[dataWords];
        for (int i = 0; i < dataWords; i++) {
            data[i] = (long) LONG_AT.get(bytes, dataAt + i * 8);
        }
        PalettedContainerRO.PackedData<T> packed = new PalettedContainerRO.PackedData<>(
            Arrays.asList(palette).subList(from, from + count), Optional.of(Arrays.stream(data)));
        return PalettedContainer.unpack(strategy, packed, defaultValue, null).getOrThrow();
    }

    private static void writeBlockEntities(RegistryFriendlyByteBuf out, SavedChunk chunk) {
        List<CompoundTag> saved = chunk.blockEntities();
        if (saved.isEmpty()) {
            out.writeVarInt(0);
            return;
        }

        List<CompoundTag> selected = new ArrayList<>();
        List<BlockEntityType<?>> types = new ArrayList<>();

        for (CompoundTag tag : saved) {
            Identifier id = Identifier.tryParse(tag.getStringOr("id", ""));
            BlockEntityType<?> type = id == null ? null : BuiltInRegistries.BLOCK_ENTITY_TYPE.getValue(id);
            if (type == null) continue;
            selected.add(tag);
            types.add(type);
        }

        out.writeVarInt(selected.size());
        for (int i = 0; i < selected.size(); i++) {
            CompoundTag tag = selected.get(i);
            int x = tag.getIntOr("x", 0);
            int y = tag.getIntOr("y", 0);
            int z = tag.getIntOr("z", 0);

            tag.remove("id");
            tag.remove("x");
            tag.remove("y");
            tag.remove("z");

            out.writeByte(SectionPos.sectionRelative(x) << 4 | SectionPos.sectionRelative(z));
            out.writeShort(y);
            BLOCK_ENTITY_TYPE.encode(out, types.get(i));
            out.writeNbt(tag.isEmpty() ? null : tag);
        }
    }

    private static void writeLightData(RegistryFriendlyByteBuf out, WorldRegionSource source, SavedChunk saved) {
        int minLightSection = source.minSectionY() - 1;
        int maxLightSection = source.maxSectionY() + 1;
        int words = Math.ceilDiv(maxLightSection - minLightSection + 1, 64);

        long[] masks = masks(4 * words);
        int sky = 0;
        int block = words;
        int emptySky = 2 * words;
        int emptyBlock = 3 * words;

        for (int y = minLightSection; y <= maxLightSection; y++) {
            SavedChunk.Section section = saved.section(y);
            if (section == null) continue;
            int index = y - minLightSection;
            if (source.hasSkyLight()) {
                mark(masks, sky, emptySky, index, section.skyLightState(), section.skyLightAt());
            }
            mark(masks, block, emptyBlock, index, section.blockLightState(), section.blockLightAt());
        }

        writeMask(out, masks, sky, words);
        writeMask(out, masks, block, words);
        writeMask(out, masks, emptySky, words);
        writeMask(out, masks, emptyBlock, words);
        writeLayers(out, saved, masks, sky, words, minLightSection, maxLightSection, true);
        writeLayers(out, saved, masks, block, words, minLightSection, maxLightSection, false);
    }

    private static void mark(long[] masks, int mask, int emptyMask, int index, int state, int at) {
        if (state == LIGHT_STATE_NULL || state == LIGHT_STATE_HIDDEN) return;
        if (state != LIGHT_STATE_INIT || at < 0) {
            set(masks, emptyMask, index);
            return;
        }
        set(masks, mask, index);
    }

    private static void set(long[] masks, int base, int index) {
        masks[base + (index >> 6)] |= 1L << (index & 63);
    }

    private static boolean isSet(long[] masks, int base, int index) {
        return (masks[base + (index >> 6)] & 1L << (index & 63)) != 0;
    }

    private static void writeMask(FriendlyByteBuf out, long[] masks, int base, int words) {
        int length = words;
        while (length > 0 && masks[base + length - 1] == 0L) length--;
        out.writeVarInt(length);
        for (int i = 0; i < length; i++) {
            out.writeLong(masks[base + i]);
        }
    }

    private static void writeLayers(FriendlyByteBuf out, SavedChunk saved, long[] masks, int base, int words,
                                    int minLightSection, int maxLightSection, boolean skyLight) {
        int count = 0;
        for (int i = 0; i < words; i++) {
            count += Long.bitCount(masks[base + i]);
        }
        out.writeVarInt(count);
        for (int y = minLightSection; y <= maxLightSection; y++) {
            if (!isSet(masks, base, y - minLightSection)) continue;
            SavedChunk.Section section = saved.section(y);
            out.writeVarInt(SavedChunk.DATA_LAYER_BYTES);
            out.writeBytes(saved.bytes(), skyLight ? section.skyLightAt() : section.blockLightAt(),
                SavedChunk.DATA_LAYER_BYTES);
        }
    }

    private static long[] masks(int words) {
        long[] masks = MASK_SCRATCH.get();
        if (masks.length < words) {
            masks = new long[words];
            MASK_SCRATCH.set(masks);
        }
        Arrays.fill(masks, 0, words, 0L);
        return masks;
    }
}
