package net.gensokyoreimagined.farview.nms;

import java.io.IOException;

public interface ChunkSource extends AutoCloseable {
    FakeChunk read(int chunkX, int chunkZ, BlockEntityPolicy blockEntities, boolean requireSavedLight) throws IOException;

    @Override
    void close();
}
