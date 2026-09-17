package net.gensokyoreimagined.farview.nms;

import java.io.ByteArrayInputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

public final class ChunkNbtInput implements DataInput {
    private static final int INITIAL_CAPACITY = 128 * 1024;
    private static final int MAX_RETAINED_CAPACITY = 4 * 1024 * 1024;

    private static final int STRING_SLOTS = 1024;
    private static final int MAX_CACHED_STRING = 64;

    private static final VarHandle SHORT_AT =
        MethodHandles.byteArrayViewVarHandle(short[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle INT_AT =
        MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.BIG_ENDIAN);
    private static final VarHandle LONG_AT =
        MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.BIG_ENDIAN);

    private final String[] strings = new String[STRING_SLOTS];
    private final Inflater inflater = new Inflater();
    private byte[] bytes = new byte[INITIAL_CAPACITY];
    private int position;
    private int limit;

    public void fill(InputStream in) throws IOException {
        prepare();
        while (true) {
            if (limit == bytes.length) grow();
            int read = in.read(bytes, limit, bytes.length - limit);
            if (read < 0) return;
            limit += read;
        }
    }

    public void inflate(byte[] compressed, int length) throws IOException {
        prepare();
        inflater.reset();
        inflater.setInput(compressed, 0, length);
        try {
            while (!inflater.finished()) {
                if (limit == bytes.length) grow();
                int written = inflater.inflate(bytes, limit, bytes.length - limit);
                if (written == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new EOFException("chunk ends mid-stream");
                }
                limit += written;
            }
        } catch (DataFormatException malformed) {
            throw new IOException(malformed);
        }
    }

    private void prepare() {
        if (bytes.length > MAX_RETAINED_CAPACITY) bytes = new byte[INITIAL_CAPACITY];
        position = 0;
        limit = 0;
    }

    private void grow() {
        bytes = Arrays.copyOf(bytes, bytes.length * 2);
    }

    public byte[] bytes() {
        return bytes;
    }

    public int position() {
        return position;
    }

    public void position(int at) {
        position = at;
    }

    public int take(int count) throws EOFException {
        int at = position;
        if (count < 0 || limit - at < count) throw new EOFException();
        position = at + count;
        return at;
    }

    public boolean equalsAt(byte[] expected, int start, int length) {
        return Arrays.equals(expected, 0, expected.length, bytes, start, start + length);
    }

    @Override
    public void readFully(byte[] target) throws IOException {
        readFully(target, 0, target.length);
    }

    @Override
    public void readFully(byte[] target, int offset, int length) throws IOException {
        System.arraycopy(bytes, take(length), target, offset, length);
    }

    @Override
    public int skipBytes(int count) {
        int skipped = Math.clamp(count, 0, limit - position);
        position += skipped;
        return skipped;
    }

    @Override
    public boolean readBoolean() throws IOException {
        return readByte() != 0;
    }

    @Override
    public byte readByte() throws IOException {
        return bytes[take(1)];
    }

    @Override
    public int readUnsignedByte() throws IOException {
        return bytes[take(1)] & 0xFF;
    }

    @Override
    public short readShort() throws IOException {
        return (short) SHORT_AT.get(bytes, take(2));
    }

    @Override
    public int readUnsignedShort() throws IOException {
        return (short) SHORT_AT.get(bytes, take(2)) & 0xFFFF;
    }

    @Override
    public char readChar() throws IOException {
        return (char) (short) SHORT_AT.get(bytes, take(2));
    }

    @Override
    public int readInt() throws IOException {
        return (int) INT_AT.get(bytes, take(4));
    }

    @Override
    public long readLong() throws IOException {
        return (long) LONG_AT.get(bytes, take(8));
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public String readLine() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String readUTF() throws IOException {
        int length = readUnsignedShort();
        int start = take(length);
        return string(start, length);
    }

    public String string(int start, int length) throws IOException {
        if (length > MAX_CACHED_STRING) return decode(start, length);

        int hash = asciiHash(start, length);
        if (hash < 0) return decode(start, length);

        int slot = hash & (STRING_SLOTS - 1);
        String cached = strings[slot];
        if (cached != null && matches(cached, start, length)) return cached;

        String decoded = new String(bytes, start, length, StandardCharsets.ISO_8859_1);
        strings[slot] = decoded;
        return decoded;
    }

    private int asciiHash(int start, int length) {
        int hash = length;
        for (int i = 0; i < length; i++) {
            byte b = bytes[start + i];
            if (b <= 0) return -1;
            hash = hash * 31 + b;
        }
        return hash & Integer.MAX_VALUE;
    }

    private boolean matches(String cached, int start, int length) {
        if (cached.length() != length) return false;
        for (int i = 0; i < length; i++) {
            if (cached.charAt(i) != (char) bytes[start + i]) return false;
        }
        return true;
    }

    private String decode(int start, int length) throws IOException {
        return new DataInputStream(new ByteArrayInputStream(bytes, start - 2, length + 2)).readUTF();
    }
}
