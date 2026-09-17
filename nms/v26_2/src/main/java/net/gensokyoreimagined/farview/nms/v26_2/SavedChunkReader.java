package net.gensokyoreimagined.farview.nms.v26_2;

import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import net.gensokyoreimagined.farview.nms.BlockEntityPolicy;
import net.gensokyoreimagined.farview.nms.ChunkNbtInput;
import net.gensokyoreimagined.farview.nms.PaletteCache;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;
import net.minecraft.world.level.levelgen.Heightmap;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class SavedChunkReader {
    private static final ThreadLocal<SavedChunkReader> SCRATCH = ThreadLocal.withInitial(SavedChunkReader::new);

    private static final byte[] STATUS = key("Status");
    private static final byte[] STATUS_FULL = key(BuiltInRegistries.CHUNK_STATUS.getKey(ChunkStatus.FULL).toString());
    private static final byte[] STATUS_FULL_BARE = key(BuiltInRegistries.CHUNK_STATUS.getKey(ChunkStatus.FULL).getPath());
    private static final byte[] LIGHT_ON = key("isLightOn");
    private static final byte[] STARLIGHT_VERSION = key(SaveUtil.STARLIGHT_VERSION_TAG);
    private static final byte[] HEIGHTMAPS = key("Heightmaps");
    private static final byte[] SECTIONS = key("sections");
    private static final byte[] BLOCK_ENTITIES = key("block_entities");
    private static final byte[] SECTION_Y = key("Y");
    private static final byte[] SKYLIGHT_STATE = key(SaveUtil.SKYLIGHT_STATE_TAG);
    private static final byte[] BLOCKLIGHT_STATE = key(SaveUtil.BLOCKLIGHT_STATE_TAG);
    private static final byte[] SKY_LIGHT = key(SerializableChunkData.SKY_LIGHT_TAG);
    private static final byte[] BLOCK_LIGHT = key(SerializableChunkData.BLOCK_LIGHT_TAG);
    private static final byte[] BLOCK_STATES = key("block_states");
    private static final byte[] BIOMES = key("biomes");
    private static final byte[] PALETTE = key("palette");
    private static final byte[] DATA = key("data");
    private static final byte[] NAME = key("Name");
    private static final byte[] PROPERTIES = key("Properties");
    private static final byte[] ID = key("id");

    private static final byte[][] CLIENT_HEIGHTMAP_KEYS;
    private static final Heightmap.Types[] CLIENT_HEIGHTMAP_TYPES;

    static {
        List<Heightmap.Types> sent = new ArrayList<>();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.sendToClient()) sent.add(type);
        }
        CLIENT_HEIGHTMAP_KEYS = new byte[sent.size()][];
        CLIENT_HEIGHTMAP_TYPES = sent.toArray(new Heightmap.Types[0]);
        for (int i = 0; i < sent.size(); i++) {
            CLIENT_HEIGHTMAP_KEYS[i] = key(sent.get(i).getSerializationKey());
        }
    }

    private static final int MAX_DEPTH = 64;

    private final ChunkNbtInput in = new ChunkNbtInput();
    private final SavedChunk chunk = new SavedChunk();
    private final PaletteCache<BlockState> blockStates = new PaletteCache<>();
    private final PaletteCache<Holder<Biome>> biomes = new PaletteCache<>();
    private final NbtAccounter accounter = NbtAccounter.unlimitedHeap();

    private HolderGetter<Biome> biomeLookup;
    private BlockState defaultBlockState;
    private Holder<Biome> defaultBiome;
    private BlockEntityPolicy policy;
    private int minSectionY;
    private int maxSectionY;

    private int nameAt;
    private int nameLength;
    private int depth;

    static SavedChunkReader forThread() {
        return SCRATCH.get();
    }

    void begin(HolderGetter<Biome> biomeLookup, BlockState defaultBlockState, Holder<Biome> defaultBiome,
               int minSectionY, int maxSectionY, BlockEntityPolicy policy) {
        if (this.biomeLookup != biomeLookup) {
            this.biomeLookup = biomeLookup;
            biomes.clear();
        }
        this.defaultBlockState = defaultBlockState;
        this.defaultBiome = defaultBiome;
        this.minSectionY = minSectionY;
        this.maxSectionY = maxSectionY;
        this.policy = policy;
    }

    SavedChunk read(InputStream stream) throws IOException {
        in.fill(stream);
        return parse();
    }

    SavedChunk readDeflated(byte[] compressed, int length) throws IOException {
        in.inflate(compressed, length);
        return parse();
    }

    private SavedChunk parse() throws IOException {
        chunk.reset(in.bytes(), minSectionY - 1, maxSectionY - minSectionY + 3);
        depth = 0;

        if (in.readByte() != Tag.TAG_COMPOUND) throw new IOException("saved chunk is not a compound");
        skipName();
        readChunk();
        return chunk;
    }

    private void readChunk() throws IOException {
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_STRING && named(STATUS)) {
                readStatus();
            } else if (type == Tag.TAG_INT && named(STARLIGHT_VERSION)) {
                chunk.starlightVersion(in.readInt());
            } else if (named(LIGHT_ON)) {
                chunk.lightOn();
                skip(type);
            } else if (type == Tag.TAG_COMPOUND && named(HEIGHTMAPS)) {
                readHeightmaps();
            } else if (type == Tag.TAG_LIST && named(SECTIONS)) {
                readSections();
            } else if (type == Tag.TAG_LIST && named(BLOCK_ENTITIES) && policy.enabled()) {
                readBlockEntities();
            } else {
                skip(type);
            }
        }
    }

    private void readStatus() throws IOException {
        int length = in.readUnsignedShort();
        int at = in.take(length);
        chunk.full(in.equalsAt(STATUS_FULL, at, length) || in.equalsAt(STATUS_FULL_BARE, at, length));
    }

    private void readHeightmaps() throws IOException {
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            Heightmap.Types heightmap = type == Tag.TAG_LONG_ARRAY ? clientHeightmap() : null;
            if (heightmap == null) {
                skip(type);
                continue;
            }
            int words = in.readInt();
            chunk.heightmap(heightmap, takeLongs(words), words);
        }
    }

    private Heightmap.Types clientHeightmap() {
        for (int i = 0; i < CLIENT_HEIGHTMAP_KEYS.length; i++) {
            if (named(CLIENT_HEIGHTMAP_KEYS[i])) return CLIENT_HEIGHTMAP_TYPES[i];
        }
        return null;
    }

    private void readSections() throws IOException {
        int element = in.readByte();
        int count = in.readInt();
        if (element != Tag.TAG_COMPOUND) {
            skipList(element, count);
            return;
        }
        for (int i = 0; i < count; i++) {
            readSection();
        }
    }

    private void readSection() throws IOException {
        SavedChunk.Section section = chunk.nextSection();
        int y = Integer.MIN_VALUE;
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_BYTE && named(SECTION_Y)) {
                y = in.readByte();
            } else if (type == Tag.TAG_INT && named(SKYLIGHT_STATE)) {
                section.skyLightState = in.readInt();
            } else if (type == Tag.TAG_INT && named(BLOCKLIGHT_STATE)) {
                section.blockLightState = in.readInt();
            } else if (type == Tag.TAG_BYTE_ARRAY && named(SKY_LIGHT)) {
                section.skyLightAt = takeLightLayer();
            } else if (type == Tag.TAG_BYTE_ARRAY && named(BLOCK_LIGHT)) {
                section.blockLightAt = takeLightLayer();
            } else if (type == Tag.TAG_COMPOUND && named(BLOCK_STATES)) {
                readBlockStates(section);
            } else if (type == Tag.TAG_COMPOUND && named(BIOMES)) {
                readBiomes(section);
            } else {
                skip(type);
            }
        }
        chunk.placeSection(section, y);
    }

    private int takeLightLayer() throws IOException {
        int length = in.readInt();
        int at = in.take(length);
        return length == SavedChunk.DATA_LAYER_BYTES ? at : -1;
    }

    private void readBlockStates(SavedChunk.Section section) throws IOException {
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_LONG_ARRAY && named(DATA)) {
                section.blockDataWords = in.readInt();
                section.blockDataAt = takeLongs(section.blockDataWords);
            } else if (type == Tag.TAG_LIST && named(PALETTE)) {
                readBlockPalette(section);
            } else {
                skip(type);
            }
        }
    }

    private void readBlockPalette(SavedChunk.Section section) throws IOException {
        int element = in.readByte();
        int count = in.readInt();
        if (element != Tag.TAG_COMPOUND || count < 0) {
            skipList(element, count);
            return;
        }
        section.blockPaletteFrom = chunk.blockPaletteTop();
        for (int i = 0; i < count; i++) {
            chunk.addBlockState(blockState());
        }
        section.blockPaletteCount = count;
    }

    private void readBiomes(SavedChunk.Section section) throws IOException {
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_LONG_ARRAY && named(DATA)) {
                section.biomeDataWords = in.readInt();
                section.biomeDataAt = takeLongs(section.biomeDataWords);
            } else if (type == Tag.TAG_LIST && named(PALETTE)) {
                readBiomePalette(section);
            } else {
                skip(type);
            }
        }
    }

    private void readBiomePalette(SavedChunk.Section section) throws IOException {
        int element = in.readByte();
        int count = in.readInt();
        if (element != Tag.TAG_STRING || count < 0) {
            skipList(element, count);
            return;
        }
        section.biomePaletteFrom = chunk.biomePaletteTop();
        for (int i = 0; i < count; i++) {
            chunk.addBiome(biome());
        }
        section.biomePaletteCount = count;
    }

    private BlockState blockState() throws IOException {
        int from = in.position();
        skipCompound();
        int length = in.position() - from;

        int hash = PaletteCache.hash(in.bytes(), from, length);
        BlockState cached = blockStates.get(hash, in.bytes(), from, length);
        if (cached != null) return cached;

        int resume = in.position();
        in.position(from);
        BlockState resolved = resolveBlockState();
        in.position(resume);
        blockStates.put(hash, in.bytes(), from, length, resolved);
        return resolved;
    }

    private BlockState resolveBlockState() throws IOException {
        String name = null;
        int propertiesAt = -1;
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_STRING && named(NAME)) {
                name = in.readUTF();
            } else if (type == Tag.TAG_COMPOUND && named(PROPERTIES)) {
                propertiesAt = in.position();
                skipCompound();
            } else {
                skip(type);
            }
        }

        Identifier id = name == null ? null : Identifier.tryParse(name);
        Block block = id == null ? null : BuiltInRegistries.BLOCK.getOptional(id).orElse(null);
        if (block == null) return defaultBlockState;

        BlockState state = block.defaultBlockState();
        return propertiesAt < 0 ? state : withProperties(state, block.getStateDefinition(), propertiesAt);
    }

    private BlockState withProperties(BlockState state, StateDefinition<Block, BlockState> definition, int at)
        throws IOException {
        int resume = in.position();
        in.position(at);
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type != Tag.TAG_STRING) {
                skip(type);
                continue;
            }
            Property<?> property = definition.getProperty(in.string(nameAt, nameLength));
            String value = in.readUTF();
            if (property != null) state = withValue(state, property, value);
        }
        in.position(resume);
        return state;
    }

    private static <T extends Comparable<T>> BlockState withValue(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }

    private Holder<Biome> biome() throws IOException {
        int length = in.readUnsignedShort();
        int at = in.take(length);

        int hash = PaletteCache.hash(in.bytes(), at, length);
        Holder<Biome> cached = biomes.get(hash, in.bytes(), at, length);
        if (cached != null) return cached;

        Identifier id = Identifier.tryParse(in.string(at, length));
        Optional<Holder.Reference<Biome>> found = id == null
            ? Optional.empty()
            : biomeLookup.get(ResourceKey.create(Registries.BIOME, id));
        Holder<Biome> resolved = found.isPresent() ? found.get() : defaultBiome;
        biomes.put(hash, in.bytes(), at, length, resolved);
        return resolved;
    }

    private void readBlockEntities() throws IOException {
        int element = in.readByte();
        int count = in.readInt();
        if (element != Tag.TAG_COMPOUND || count < 0) {
            skipList(element, count);
            return;
        }
        List<CompoundTag> kept = chunk.blockEntities();
        for (int i = 0; i < count; i++) {
            int from = in.position();
            boolean allowed = kept.size() < policy.maxPerChunk() && allowedBlockEntity();
            in.position(from);
            if (allowed) {
                kept.add(CompoundTag.TYPE.load(in, accounter));
            } else {
                skipCompound();
            }
        }
    }

    private boolean allowedBlockEntity() throws IOException {
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            readName();
            if (type == Tag.TAG_STRING && named(ID)) {
                Identifier id = Identifier.tryParse(in.readUTF());
                return id != null && policy.allows(id.toString());
            }
            skip(type);
        }
        return false;
    }

    private void readName() throws IOException {
        nameLength = in.readUnsignedShort();
        nameAt = in.take(nameLength);
    }

    private boolean named(byte[] key) {
        return in.equalsAt(key, nameAt, nameLength);
    }

    private void skipName() throws IOException {
        in.take(in.readUnsignedShort());
    }

    private void skip(int type) throws IOException {
        switch (type) {
            case Tag.TAG_BYTE -> in.take(1);
            case Tag.TAG_SHORT -> in.take(2);
            case Tag.TAG_INT, Tag.TAG_FLOAT -> in.take(4);
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> in.take(8);
            case Tag.TAG_BYTE_ARRAY -> in.take(in.readInt());
            case Tag.TAG_STRING -> skipName();
            case Tag.TAG_LIST -> skipList(in.readByte(), in.readInt());
            case Tag.TAG_COMPOUND -> skipCompound();
            case Tag.TAG_INT_ARRAY -> takeFixed(in.readInt(), 4);
            case Tag.TAG_LONG_ARRAY -> takeLongs(in.readInt());
            default -> throw new IOException("unknown tag type " + type);
        }
    }

    private void skipCompound() throws IOException {
        enter();
        int type;
        while ((type = in.readByte()) != Tag.TAG_END) {
            skipName();
            skip(type);
        }
        depth--;
    }

    private void skipList(int element, int count) throws IOException {
        if (count <= 0) return;
        int fixed = switch (element) {
            case Tag.TAG_BYTE -> 1;
            case Tag.TAG_SHORT -> 2;
            case Tag.TAG_INT, Tag.TAG_FLOAT -> 4;
            case Tag.TAG_LONG, Tag.TAG_DOUBLE -> 8;
            default -> 0;
        };
        if (fixed != 0) {
            takeFixed(count, fixed);
            return;
        }
        enter();
        for (int i = 0; i < count; i++) {
            skip(element);
        }
        depth--;
    }

    private int takeLongs(int words) throws IOException {
        return takeFixed(words, 8);
    }

    private int takeFixed(int count, int size) throws IOException {
        if (count < 0 || count > Integer.MAX_VALUE / size) throw new IOException("saved array of " + count);
        return in.take(count * size);
    }

    private void enter() throws IOException {
        if (++depth > MAX_DEPTH) throw new IOException("saved chunk nests deeper than " + MAX_DEPTH);
    }

    private static byte[] key(String name) {
        return name.getBytes(StandardCharsets.ISO_8859_1);
    }
}
