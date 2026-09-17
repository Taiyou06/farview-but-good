package net.gensokyoreimagined.farview.nms.v1_21_8;

import net.minecraft.world.level.chunk.storage.RegionFileVersion;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class RegionReader implements AutoCloseable {
    private static final int SECTOR_BYTES = 4096;
    private static final int HEADER_SECTORS = 2;
    private static final int EXTERNAL_STREAM_FLAG = 0x80;
    private static final int VERSION_ID_MASK = 0x7F;

    private static final int MAX_SECTORS = 1024;

    private static final ThreadLocal<ByteBuffer> HEADER_SCRATCH =
        ThreadLocal.withInitial(() -> ByteBuffer.allocate(5));
    private static final ThreadLocal<byte[]> PAYLOAD_SCRATCH =
        ThreadLocal.withInitial(() -> new byte[16 * 1024]);

    private final Path regionDir;
    private final FileChannel channel;

    private RegionReader(Path regionDir, FileChannel channel) {
        this.regionDir = regionDir;
        this.channel = channel;
    }

    public static RegionReader open(Path regionDir, int regionX, int regionZ) throws IOException {
        Path file = regionDir.resolve("r." + regionX + "." + regionZ + ".mca");
        if (!Files.isRegularFile(file)) return null;
        return new RegionReader(regionDir, FileChannel.open(file, StandardOpenOption.READ));
    }

    public SavedChunk read(int chunkX, int chunkZ, SavedChunkReader into) throws IOException {
        int slot = (chunkX & 31) + (chunkZ & 31) * 32;

        ByteBuffer header = HEADER_SCRATCH.get();
        if (!readFully(header.clear().limit(4), (long) slot * 4)) return null;
        int entry = header.getInt(0);
        if (entry == 0) return null;

        int sector = entry >>> 8;
        int sectorCount = entry & 0xFF;
        if (sector < HEADER_SECTORS) return null;

        long base = (long) sector * SECTOR_BYTES;

        if (!readFully(header.clear().limit(5), base)) return null;
        int length = header.getInt(0);
        byte versionId = header.get(4);
        if (length <= 0) return null;

        if (sectorCount == 255) {
            sectorCount = (length + 4) / SECTOR_BYTES + 1;
        }
        if (sectorCount <= 0 || sectorCount > MAX_SECTORS) return null;

        RegionFileVersion version = RegionFileVersion.fromId(versionId & VERSION_ID_MASK);
        if (version == null) return null;

        if ((versionId & EXTERNAL_STREAM_FLAG) != 0) {
            return readExternal(chunkX, chunkZ, version, into);
        }

        int streamLength = length - 1;
        if (streamLength > sectorCount * SECTOR_BYTES - 5) return null;
        byte[] compressed = payload(streamLength);
        if (!readFully(ByteBuffer.wrap(compressed, 0, streamLength), base + 5)) return null;

        if (version == RegionFileVersion.VERSION_DEFLATE) {
            return into.readDeflated(compressed, streamLength);
        }
        try (InputStream in = version.wrap(new ByteArrayInputStream(compressed, 0, streamLength))) {
            return into.read(in);
        }
    }

    private static byte[] payload(int length) {
        byte[] payload = PAYLOAD_SCRATCH.get();
        if (payload.length < length) {
            payload = new byte[length];
            PAYLOAD_SCRATCH.set(payload);
        }
        return payload;
    }

    private SavedChunk readExternal(int chunkX, int chunkZ, RegionFileVersion version, SavedChunkReader into)
        throws IOException {
        Path external = regionDir.resolve("c." + chunkX + "." + chunkZ + ".mcc");
        if (!Files.isRegularFile(external)) return null;
        try (InputStream raw = Files.newInputStream(external);
             InputStream in = version.wrap(raw)) {
            return into.read(in);
        }
    }

    private boolean readFully(ByteBuffer buffer, long position) throws IOException {
        long at = position;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, at);
            if (read < 0) return false;
            at += read;
        }
        buffer.flip();
        return true;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
