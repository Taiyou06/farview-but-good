package net.gensokyoreimagined.farview.nms;

import java.io.IOException;

public interface ChunkSource extends AutoCloseable {
    PreparedChunk read(int chunkX, int chunkZ, BlockEntityPolicy blockEntities, boolean requireSavedLight,
                       boolean cullSections) throws IOException;

    Object packet(PreparedChunk prepared, long[] realSections);

    int minSectionY();

    int sectionCount();

    @Override
    void close();
}
