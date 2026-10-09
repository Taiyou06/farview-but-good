package net.gensokyoreimagined.farview.nms.v26_2;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import net.gensokyoreimagined.farview.nms.PreparedChunk;
import net.gensokyoreimagined.farview.nms.SectionFaces;
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

    private static final ThreadLocal<int[]> HISTOGRAM_SCRATCH =
        ThreadLocal.withInitial(() -> new int[1 << MAX_LOCAL_BLOCK_BITS]);
    private static final ThreadLocal<boolean[]> SOLID_SCRATCH =
        ThreadLocal.withInitial(() -> new boolean[1 << MAX_LOCAL_BLOCK_BITS]);
    private static final ThreadLocal<long[]> COUNTS_SCRATCH = ThreadLocal.withInitial(() -> new long[0]);

    private static final ThreadLocal<long[]> MASK_SCRATCH = ThreadLocal.withInitial(() -> new long[4]);

    private static final VarHandle LONG_AT =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private FakeChunkPacketFactory() {}

    static PreparedChunk prepare(WorldRegionSource source, int chunkX, int chunkZ, SavedChunk saved,
                                 boolean cullSections) {
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(scratch(PACKET_SCRATCH), source.registryAccess());
        out.writeInt(chunkX);
        out.writeInt(chunkZ);
        writeHeightmaps(out, saved);

        int minSectionY = source.minSectionY();
        int sections = source.sectionCount();
        int entryCount = source.containerFactory().blockStatesStrategy().entryCount();
        long[] counts = counts(sections);
        SectionFaces faces = cullSections ? SectionFaces.forThread().begin(sections) : null;
        for (int i = 0; i < sections; i++) {
            SavedChunk.Section section = saved.section(minSectionY + i);
            int blockCount = section == null ? -1 : section.blockPaletteCount();
            if (blockCount < 0) {
                counts[i] = 0L;
                if (faces != null) faces.openSection(i);
                continue;
            }
            counts[i] = countBlocks(saved.blockPalette(), section.blockPaletteFrom(), blockCount, saved.bytes(),
                section.blockDataAt(), section.blockDataWords(),
                storedBits(blockCount, MIN_BLOCK_BITS), entryCount, faces, i);
        }

        int[] pieces = new int[sections * 4 + 2];
        pieces[0] = out.writerIndex();
        for (int i = 0; i < sections; i++) {
            SavedChunk.Section section = saved.section(minSectionY + i);
            int at = 1 + i * 4;
            pieces[at] = out.writerIndex();
            writeSection(out, source.containerFactory(), saved, section, counts[i], -1);
            pieces[at + 1] = out.writerIndex();
            int filler = faces == null ? -1 : faces.filler(i);
            if (filler >= 0 && section.blockPaletteCount() > 1) {
                pieces[at + 2] = out.writerIndex();
                writeSection(out, source.containerFactory(), saved, section, counts[i], filler);
                pieces[at + 3] = out.writerIndex();
            } else {
                pieces[at + 2] = pieces[at];
                pieces[at + 3] = pieces[at + 1];
            }
        }
        pieces[pieces.length - 1] = out.writerIndex();
        writeBlockEntities(out, saved);
        writeLightData(out, source, saved);

        byte[] data = new byte[out.writerIndex()];
        out.getBytes(0, data);
        return new PreparedChunk(data, pieces, faces == null ? null : faces.faces(surface(saved, sections)));
    }

    static ClientboundLevelChunkWithLightPacket packet(WorldRegionSource source, PreparedChunk prepared,
                                                      long[] realSections) {
        RegistryFriendlyByteBuf out = new RegistryFriendlyByteBuf(scratch(PACKET_SCRATCH), source.registryAccess());
        byte[] data = prepared.data();
        int[] pieces = prepared.pieces();
        int sections = (pieces.length - 2) / 4;
        out.writeBytes(data, 0, pieces[0]);
        int size = 0;
        for (int i = 0; i < sections; i++) {
            int at = piece(i, realSections);
            size += pieces[at + 1] - pieces[at];
        }
        out.writeVarInt(size);
        for (int i = 0; i < sections; i++) {
            int at = piece(i, realSections);
            out.writeBytes(data, pieces[at], pieces[at + 1] - pieces[at]);
        }
        int tail = pieces[pieces.length - 1];
        out.writeBytes(data, tail, data.length - tail);

        ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(out);
        packet.setReady(true);
        return packet;
    }

    private static int piece(int section, long[] realSections) {
        return 1 + section * 4 + ((realSections[section >>> 6] >>> section & 1L) != 0L ? 0 : 2);
    }

    private static ByteBuf scratch(ThreadLocal<ByteBuf> holder) {
        ByteBuf buffer = holder.get();
        if (buffer.capacity() > SCRATCH_MAX_BYTES) {
            buffer = Unpooled.buffer(SCRATCH_INITIAL_BYTES);
            holder.set(buffer);
        }
        return buffer.clear();
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
                                     SavedChunk saved, SavedChunk.Section section, long counts, int filler) {
        Strategy<BlockState> blockStrategy = factory.blockStatesStrategy();
        if (filler >= 0) counts = tally(saved.blockPalette()[filler], blockStrategy.entryCount(), 0L);

        out.writeShort((int) (counts >>> 32));
        out.writeShort((int) counts);
        if (section == null) {
            writeAbsent(out, blockStrategy, factory.defaultBlockState());
            writeAbsent(out, factory.biomeStrategy(), factory.defaultBiome());
            return;
        }
        if (filler >= 0) {
            writeAbsent(out, blockStrategy, saved.blockPalette()[filler]);
        } else {
            writeContainer(out, blockStrategy, factory.defaultBlockState(),
                saved.blockPalette(), section.blockPaletteFrom(), section.blockPaletteCount(),
                saved.bytes(), section.blockDataAt(), section.blockDataWords(),
                MIN_BLOCK_BITS, MAX_LOCAL_BLOCK_BITS);
        }
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
                            int bits, int entryCount, SectionFaces faces, int section) {
        if (count == 0) throw new IllegalArgumentException("saved section holds an empty palette");
        if (bits == 0) {
            if (faces != null) {
                if (palette[from].isSolidRender()) faces.filler(section, from);
                else faces.openSection(section);
            }
            return tally(palette[from], entryCount, 0L);
        }

        checkPacked(dataWords, entryCount, bits);
        int[] histogram = histogram(1 << bits);
        boolean[] solid = faces == null ? null : solid(palette, from, count, 1 << bits);
        long mask = (1L << bits) - 1L;
        int valuesPerLong = 64 / bits;
        long open = 0L;
        for (int word = 0, index = 0; index < entryCount; word++) {
            long cell = (long) LONG_AT.get(bytes, dataAt + word * 8);
            int n = Math.min(valuesPerLong, entryCount - index);
            for (int i = 0; i < n; i++, index++) {
                int id = (int) (cell & mask);
                histogram[id]++;
                cell >>>= bits;
                if (solid == null) continue;
                if (!solid[id]) open |= 1L << index;
                if ((index & 63) == 63) {
                    faces.openWord(section, index >>> 6, open);
                    open = 0L;
                }
            }
        }

        long counts = 0L;
        int filler = -1;
        for (int id = 0; id < (1 << bits); id++) {
            if (histogram[id] == 0) continue;
            if (id >= count) {
                throw new IllegalArgumentException("saved section indexes palette entry " + id + " of " + count);
            }
            counts = tally(palette[from + id], histogram[id], counts);
            if (solid != null && solid[id] && (filler < 0 || histogram[id] > histogram[filler])) filler = id;
        }
        if (filler >= 0) faces.filler(section, from + filler);
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

    private static boolean[] solid(BlockState[] palette, int from, int count, int size) {
        boolean[] solid = SOLID_SCRATCH.get();
        if (solid.length < size) {
            solid = new boolean[size];
            SOLID_SCRATCH.set(solid);
        }
        for (int id = 0; id < count; id++) {
            solid[id] = palette[from + id].isSolidRender();
        }
        return solid;
    }

    private static long[] counts(int sections) {
        long[] counts = COUNTS_SCRATCH.get();
        if (counts.length < sections) {
            counts = new long[sections];
            COUNTS_SCRATCH.set(counts);
        }
        return counts;
    }

    private static int surface(SavedChunk saved, int sections) {
        for (int i = 0; i < saved.heightmapCount(); i++) {
            if (saved.heightmapType(i) != Heightmap.Types.WORLD_SURFACE) continue;
            int bits = Mth.ceillog2(sections * 16 + 1);
            int perWord = 64 / bits;
            long mask = (1L << bits) - 1;
            int lowest = Integer.MAX_VALUE;
            for (int column = 0; column < 256; column++) {
                long word = (long) LONG_AT.get(saved.bytes(), saved.heightmapAt(i) + column / perWord * 8);
                lowest = Math.min(lowest, (int) (word >>> (column % perWord * bits) & mask));
            }
            return Math.max(0, (lowest - 1) >> 4);
        }
        return sections;
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
