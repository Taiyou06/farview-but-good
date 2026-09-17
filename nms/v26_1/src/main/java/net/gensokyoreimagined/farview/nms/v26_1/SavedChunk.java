package net.gensokyoreimagined.farview.nms.v26_1;

import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import net.minecraft.core.Holder;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class SavedChunk {
    public static final int DATA_LAYER_BYTES = 2048;

    public static final class Section {
        int blockPaletteFrom;
        int blockPaletteCount;
        int blockDataAt;
        int blockDataWords;
        int biomePaletteFrom;
        int biomePaletteCount;
        int biomeDataAt;
        int biomeDataWords;
        int skyLightState;
        int blockLightState;
        int skyLightAt;
        int blockLightAt;

        void reset() {
            blockPaletteFrom = 0;
            blockPaletteCount = -1;
            blockDataAt = -1;
            blockDataWords = 0;
            biomePaletteFrom = 0;
            biomePaletteCount = -1;
            biomeDataAt = -1;
            biomeDataWords = 0;
            skyLightState = 0;
            blockLightState = 0;
            skyLightAt = -1;
            blockLightAt = -1;
        }

        public int blockPaletteFrom() { return blockPaletteFrom; }
        public int blockPaletteCount() { return blockPaletteCount; }
        public int blockDataAt() { return blockDataAt; }
        public int blockDataWords() { return blockDataWords; }
        public int biomePaletteFrom() { return biomePaletteFrom; }
        public int biomePaletteCount() { return biomePaletteCount; }
        public int biomeDataAt() { return biomeDataAt; }
        public int biomeDataWords() { return biomeDataWords; }
        public int skyLightState() { return skyLightState; }
        public int blockLightState() { return blockLightState; }

        public int skyLightAt() { return skyLightAt; }
        public int blockLightAt() { return blockLightAt; }
    }

    private byte[] bytes;
    private boolean full;
    private boolean lightOn;
    private int starlightVersion;

    private final Heightmap.Types[] heightmapTypes = new Heightmap.Types[Heightmap.Types.values().length];
    private final int[] heightmapAt = new int[heightmapTypes.length];
    private final int[] heightmapWords = new int[heightmapTypes.length];
    private int heightmapCount;

    private Section[] pool = new Section[0];
    private int pooled;
    private Section[] byY = new Section[0];
    private int firstY;

    private BlockState[] blockPalette = new BlockState[0];
    private int blockPaletteTop;
    private Holder<Biome>[] biomePalette = newBiomePalette(0);
    private int biomePaletteTop;

    private final List<CompoundTag> blockEntities = new ArrayList<>();

    public byte[] bytes() { return bytes; }
    public boolean full() { return full; }
    public int heightmapCount() { return heightmapCount; }
    public Heightmap.Types heightmapType(int index) { return heightmapTypes[index]; }
    public int heightmapAt(int index) { return heightmapAt[index]; }
    public int heightmapWords(int index) { return heightmapWords[index]; }
    public BlockState[] blockPalette() { return blockPalette; }
    public Holder<Biome>[] biomePalette() { return biomePalette; }
    public List<CompoundTag> blockEntities() { return blockEntities; }

    public Section section(int y) {
        int index = y - firstY;
        return index >= 0 && index < byY.length ? byY[index] : null;
    }

    public boolean hasValidLight() {
        return lightOn && starlightVersion == SaveUtil.STARLIGHT_LIGHT_VERSION;
    }

    void reset(byte[] bytes, int firstY, int sections) {
        this.bytes = bytes;
        this.full = false;
        this.lightOn = false;
        this.starlightVersion = -1;
        this.heightmapCount = 0;
        this.pooled = 0;
        this.blockPaletteTop = 0;
        this.biomePaletteTop = 0;
        this.blockEntities.clear();

        this.firstY = firstY;
        if (byY.length != sections) byY = new Section[sections];
        else Arrays.fill(byY, null);
    }

    void full(boolean full) { this.full = full; }
    void lightOn() { this.lightOn = true; }
    void starlightVersion(int version) { this.starlightVersion = version; }

    void heightmap(Heightmap.Types type, int at, int words) {
        if (heightmapCount == heightmapTypes.length) return;
        heightmapTypes[heightmapCount] = type;
        heightmapAt[heightmapCount] = at;
        heightmapWords[heightmapCount] = words;
        heightmapCount++;
    }

    Section nextSection() {
        if (pooled == pool.length) {
            pool = Arrays.copyOf(pool, Math.max(8, pool.length * 2));
            for (int i = pooled; i < pool.length; i++) pool[i] = new Section();
        }
        Section section = pool[pooled++];
        section.reset();
        return section;
    }

    void placeSection(Section section, int y) {
        int index = y - firstY;
        if (index >= 0 && index < byY.length) byY[index] = section;
    }

    int blockPaletteTop() { return blockPaletteTop; }

    void addBlockState(BlockState state) {
        if (blockPaletteTop == blockPalette.length) {
            blockPalette = Arrays.copyOf(blockPalette, Math.max(64, blockPalette.length * 2));
        }
        blockPalette[blockPaletteTop++] = state;
    }

    int biomePaletteTop() { return biomePaletteTop; }

    void addBiome(Holder<Biome> biome) {
        if (biomePaletteTop == biomePalette.length) {
            biomePalette = Arrays.copyOf(biomePalette, Math.max(64, biomePalette.length * 2));
        }
        biomePalette[biomePaletteTop++] = biome;
    }

    @SuppressWarnings("unchecked")
    private static Holder<Biome>[] newBiomePalette(int size) {
        return new Holder[size];
    }
}
