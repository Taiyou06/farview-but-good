package net.gensokyoreimagined.farview.region;

import java.util.Arrays;

final class PaletteCache<T> {

    private static final int SLOTS = 4096;

    private final byte[][] keys = new byte[SLOTS][];
    private final Object[] values = new Object[SLOTS];

    static int hash(byte[] bytes, int start, int length) {
        int hash = length;
        for (int i = 0; i < length; i++) {
            hash = hash * 31 + bytes[start + i];
        }
        return hash;
    }

    /** Null when those bytes have not been resolved on this thread yet. */
    @SuppressWarnings("unchecked")
    T get(int hash, byte[] bytes, int start, int length) {
        int slot = hash & (SLOTS - 1);
        byte[] key = keys[slot];
        if (key == null || !Arrays.equals(key, 0, key.length, bytes, start, start + length)) return null;
        return (T) values[slot];
    }

    void put(int hash, byte[] bytes, int start, int length, T value) {
        int slot = hash & (SLOTS - 1);
        keys[slot] = Arrays.copyOfRange(bytes, start, start + length);
        values[slot] = value;
    }

    void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
    }
}
