package net.gensokyoreimagined.farview.nms;

import java.util.Arrays;

public final class PaletteCache<T> {
    private static final int SLOTS = 4096;

    private final byte[][] keys = new byte[SLOTS][];
    private final Object[] values = new Object[SLOTS];

    public static int hash(byte[] bytes, int start, int length) {
        int hash = length;
        for (int i = 0; i < length; i++) {
            hash = hash * 31 + bytes[start + i];
        }
        return hash;
    }

    @SuppressWarnings("unchecked")
    public T get(int hash, byte[] bytes, int start, int length) {
        int slot = hash & (SLOTS - 1);
        byte[] key = keys[slot];
        if (key == null || !Arrays.equals(key, 0, key.length, bytes, start, start + length)) return null;
        return (T) values[slot];
    }

    public void put(int hash, byte[] bytes, int start, int length, T value) {
        int slot = hash & (SLOTS - 1);
        keys[slot] = Arrays.copyOfRange(bytes, start, start + length);
        values[slot] = value;
    }

    public void clear() {
        Arrays.fill(keys, null);
        Arrays.fill(values, null);
    }
}
