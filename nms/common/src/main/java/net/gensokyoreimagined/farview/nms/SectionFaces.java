package net.gensokyoreimagined.farview.nms;

import java.util.Arrays;

public final class SectionFaces {
    public static final int FACES = 6;
    public static final int PER_SECTION = FACES * FACES;
    public static final short EMPTY = 0x0001;
    public static final short FULL = (short) 0xF0F0;

    public static final int DOWN = 0;
    public static final int UP = 1;
    public static final int NORTH = 2;
    public static final int SOUTH = 3;
    public static final int WEST = 4;
    public static final int EAST = 5;

    private static final ThreadLocal<SectionFaces> SCRATCH = ThreadLocal.withInitial(SectionFaces::new);

    private static final int WORDS = 64;
    private static final long LOW_X = 0x0001_0001_0001_0001L;
    private static final long HIGH_X = 0x8000_8000_8000_8000L;
    private static final long LOW_ROW = 0x0000_0000_0000_FFFFL;
    private static final long HIGH_ROW = 0xFFFF_0000_0000_0000L;

    private long[] open = new long[0];
    private int[] fillers = new int[0];
    private int sections;

    private final long[] seed = new long[WORDS];
    private final long[] reached = new long[WORDS];
    private final int[] queue = new int[WORDS];

    public static SectionFaces forThread() {
        return SCRATCH.get();
    }

    public SectionFaces begin(int sections) {
        int words = sections * WORDS;
        if (open.length < words) {
            open = new long[words];
            fillers = new int[sections];
        }
        this.sections = sections;
        Arrays.fill(open, 0, words, 0L);
        Arrays.fill(fillers, 0, sections, -1);
        return this;
    }

    public void openSection(int section) {
        Arrays.fill(open, section * WORDS, (section + 1) * WORDS, -1L);
    }

    public void openWord(int section, int word, long cells) {
        open[section * WORDS + word] = cells;
    }

    public void filler(int section, int paletteIndex) {
        fillers[section] = paletteIndex;
    }

    public int filler(int section) {
        return fillers[section];
    }

    public short[] faces(int surface) {
        int clear = sections * PER_SECTION;
        int tail = clear + ((sections + 15) >>> 4);
        short[] faces = new short[tail + 1];
        for (int section = 0; section < sections; section++) {
            if (section(section, faces, section * PER_SECTION)) {
                faces[clear + (section >>> 4)] |= (short) (1 << (section & 15));
            }
        }
        faces[tail] = (short) surface;
        return faces;
    }

    private boolean section(int section, short[] out, int at) {
        int base = section * WORDS;
        long any = 0L;
        long all = -1L;
        for (int word = 0; word < WORDS; word++) {
            any |= open[base + word];
            all &= open[base + word];
        }
        if (any == 0L) {
            Arrays.fill(out, at, at + PER_SECTION, EMPTY);
            return false;
        }
        if (all == -1L) {
            Arrays.fill(out, at, at + PER_SECTION, FULL);
            return true;
        }
        for (int face = 0; face < FACES; face++) {
            out[at + face * FACES + face] = rect(face, open, base);
        }
        for (int entry = 0; entry < FACES; entry++) {
            if (out[at + entry * FACES + entry] == EMPTY) {
                for (int exit = 0; exit < FACES; exit++) {
                    if (exit != entry) out[at + entry * FACES + exit] = EMPTY;
                }
                continue;
            }
            for (int word = 0; word < WORDS; word++) {
                seed[word] = open[base + word] & faceCells(entry, word);
            }
            flood(base);
            for (int exit = 0; exit < FACES; exit++) {
                if (exit != entry) out[at + entry * FACES + exit] = rect(exit, reached, 0);
            }
        }
        return false;
    }

    private static long faceCells(int face, int word) {
        return switch (face) {
            case DOWN -> word < 4 ? -1L : 0L;
            case UP -> word >= WORDS - 4 ? -1L : 0L;
            case NORTH -> (word & 3) == 0 ? LOW_ROW : 0L;
            case SOUTH -> (word & 3) == 3 ? HIGH_ROW : 0L;
            case WEST -> LOW_X;
            default -> HIGH_X;
        };
    }

    private void flood(int base) {
        long queued = 0L;
        int head = 0;
        int tail = 0;
        int pending = 0;
        for (int word = 0; word < WORDS; word++) {
            reached[word] = seed[word];
            if (seed[word] == 0L) continue;
            queue[tail] = word;
            tail = (tail + 1) & (WORDS - 1);
            queued |= 1L << word;
            pending++;
        }
        while (pending > 0) {
            int word = queue[head];
            head = (head + 1) & (WORDS - 1);
            pending--;
            queued &= ~(1L << word);

            long cells = close(reached[word], open[base + word]);
            reached[word] = cells;
            for (int step = 0; step < 4; step++) {
                int next;
                long into;
                switch (step) {
                    case 0 -> { next = word + 4; into = cells; }
                    case 1 -> { next = word - 4; into = cells; }
                    case 2 -> { next = (word & 3) == 3 ? -1 : word + 1; into = cells >>> 48; }
                    default -> { next = (word & 3) == 0 ? -1 : word - 1; into = cells << 48; }
                }
                if (next < 0 || next >= WORDS) continue;
                long added = into & open[base + next] & ~reached[next];
                if (added == 0L) continue;
                reached[next] |= added;
                if ((queued & 1L << next) != 0L) continue;
                queued |= 1L << next;
                queue[tail] = next;
                tail = (tail + 1) & (WORDS - 1);
                pending++;
            }
        }
    }

    static long close(long cells, long open) {
        long previous;
        do {
            previous = cells;
            long pass = open & ~LOW_X;
            cells |= pass & (cells << 1);
            pass &= pass << 1;
            cells |= pass & (cells << 2);
            pass &= pass << 2;
            cells |= pass & (cells << 4);
            pass &= pass << 4;
            cells |= pass & (cells << 8);

            pass = open & ~HIGH_X;
            cells |= pass & (cells >>> 1);
            pass &= pass >>> 1;
            cells |= pass & (cells >>> 2);
            pass &= pass >>> 2;
            cells |= pass & (cells >>> 4);
            pass &= pass >>> 4;
            cells |= pass & (cells >>> 8);

            cells |= open & (cells << 16);
            cells |= open & (open << 16) & (cells << 32);
            cells |= open & (cells >>> 16);
            cells |= open & (open >>> 16) & (cells >>> 32);
        } while (cells != previous);
        return cells;
    }

    private static short rect(int face, long[] cells, int base) {
        int u0 = 16;
        int u1 = -1;
        int v0 = 16;
        int v1 = -1;
        switch (face) {
            case DOWN, UP -> {
                int layer = face == DOWN ? 0 : 15;
                for (int quarter = 0; quarter < 4; quarter++) {
                    long word = cells[base + layer * 4 + quarter];
                    for (int row = 0; row < 4 && word != 0L; row++) {
                        int bits = (int) (word >>> (row * 16)) & 0xFFFF;
                        if (bits == 0) continue;
                        int z = quarter * 4 + row;
                        v0 = Math.min(v0, z);
                        v1 = Math.max(v1, z);
                        u0 = Math.min(u0, Integer.numberOfTrailingZeros(bits));
                        u1 = Math.max(u1, 31 - Integer.numberOfLeadingZeros(bits));
                    }
                }
            }
            case NORTH, SOUTH -> {
                int quarter = face == NORTH ? 0 : 3;
                int shift = face == NORTH ? 0 : 48;
                for (int y = 0; y < 16; y++) {
                    int bits = (int) (cells[base + y * 4 + quarter] >>> shift) & 0xFFFF;
                    if (bits == 0) continue;
                    v0 = Math.min(v0, y);
                    v1 = Math.max(v1, y);
                    u0 = Math.min(u0, Integer.numberOfTrailingZeros(bits));
                    u1 = Math.max(u1, 31 - Integer.numberOfLeadingZeros(bits));
                }
            }
            default -> {
                long column = face == WEST ? LOW_X : HIGH_X;
                int shift = face == WEST ? 0 : 15;
                for (int y = 0; y < 16; y++) {
                    for (int quarter = 0; quarter < 4; quarter++) {
                        long word = cells[base + y * 4 + quarter] & column;
                        if (word == 0L) continue;
                        v0 = Math.min(v0, y);
                        v1 = Math.max(v1, y);
                        for (int row = 0; row < 4; row++) {
                            if ((word >>> (row * 16 + shift) & 1L) == 0L) continue;
                            int z = quarter * 4 + row;
                            u0 = Math.min(u0, z);
                            u1 = Math.max(u1, z);
                        }
                    }
                }
            }
        }
        if (u1 < 0) return EMPTY;
        return (short) (u0 | u1 << 4 | v0 << 8 | v1 << 12);
    }
}
