package net.gensokyoreimagined.farview.nms;

import java.util.Arrays;

import static net.gensokyoreimagined.farview.nms.SectionFaces.DOWN;
import static net.gensokyoreimagined.farview.nms.SectionFaces.EAST;
import static net.gensokyoreimagined.farview.nms.SectionFaces.EMPTY;
import static net.gensokyoreimagined.farview.nms.SectionFaces.FACES;
import static net.gensokyoreimagined.farview.nms.SectionFaces.NORTH;
import static net.gensokyoreimagined.farview.nms.SectionFaces.PER_SECTION;
import static net.gensokyoreimagined.farview.nms.SectionFaces.SOUTH;
import static net.gensokyoreimagined.farview.nms.SectionFaces.UP;
import static net.gensokyoreimagined.farview.nms.SectionFaces.WEST;

public final class OcclusionGraph {
    public static final double MARGIN = 2.0;

    private static final ThreadLocal<OcclusionGraph> SCRATCH = ThreadLocal.withInitial(OcclusionGraph::new);

    private static final int START = 1 << FACES;
    private static final int STEPS = 8;
    private static final int SIDE = 16 * STEPS;
    private static final double UNIT = 1.0 / STEPS;
    private static final double FAR = 1.0e7;
    private static final double EDGE = 1.0e-4;

    private byte[] entry = new byte[0];
    private int[] slot = new int[0];
    private char[] image = new char[0];
    private long[] queued = new long[0];
    private int[] queue = new int[0];
    private long[] clear = new long[0];

    private int width;
    private int sections;
    private int words;
    private int nodes;
    private int slots;
    private int originX;
    private int originZ;
    private int minY;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private short[][] columns;
    private long[] masks;
    private int head;
    private int tail;
    private int pending;

    public static OcclusionGraph forThread() {
        return SCRATCH.get();
    }

    public static int words(int sections) {
        return (sections + Long.SIZE - 1) >>> 6;
    }

    public void trace(double x, double y, double z, int centerX, int centerZ, int radius,
                      int minSectionY, int sections, boolean heightmap, short[][] columns, long[] masks) {
        this.width = radius * 2 + 1;
        this.sections = sections;
        this.words = words(sections);
        this.nodes = width * width * sections;
        if (entry.length < nodes) {
            entry = new byte[nodes];
            slot = new int[nodes];
            queued = new long[(nodes + 63) >>> 6];
            queue = new int[nodes];
        }
        if (clear.length < width * width * words) clear = new long[width * width * words];
        Arrays.fill(entry, 0, nodes, (byte) 0);
        Arrays.fill(queued, 0, (nodes + 63) >>> 6, 0L);
        Arrays.fill(masks, 0, width * width * words, 0L);
        this.originX = (centerX - radius) << 4;
        this.originZ = (centerZ - radius) << 4;
        this.minY = minSectionY << 4;
        this.eyeX = offEdge(x);
        this.eyeY = offEdge(y);
        this.eyeZ = offEdge(z);
        this.columns = columns;
        this.masks = masks;
        this.slots = 0;
        this.head = 0;
        this.tail = 0;
        this.pending = 0;
        int surfaceAt = sections * PER_SECTION + ((sections + 15) >>> 4);
        for (int column = 0; column < width * width; column++) {
            open(column);
            if (heightmap && columns[column] != null) mark(column, columns[column][surfaceAt], sections - 1);
        }

        int gx = Math.floorDiv((int) Math.floor(eyeX) - originX, 16);
        int gz = Math.floorDiv((int) Math.floor(eyeZ) - originZ, 16);
        int sy = Math.floorDiv((int) Math.floor(eyeY) - minY, 16);
        if (gx < 0 || gx >= width || gz < 0 || gz >= width) {
            for (int column = 0; column < width * width; column++) mark(column, 0, sections - 1);
        } else if (sy >= 0 && sy < sections) {
            int column = gz * width + gx;
            int low = low(column, sy);
            int key = column * sections + low;
            claim(key, column, low, high(column, low));
            entry[key] = (byte) START;
            push(key);
        } else {
            boolean below = sy < 0;
            for (int column = 0; column < width * width; column++) {
                enter(column, below ? 0 : sections - 1, below ? DOWN : UP, 0, 16, 0, 16);
            }
        }

        while (pending > 0) {
            int key = queue[head];
            head = head + 1 == nodes ? 0 : head + 1;
            pending--;
            queued[key >>> 6] &= ~(1L << key);
            expand(key);
        }
        this.columns = null;
        this.masks = null;
    }

    private void open(int column) {
        short[] faces = columns[column];
        int clearAt = sections * PER_SECTION;
        int clearEnd = clearAt + ((sections + 15) >>> 4);
        for (int word = 0; word < words; word++) {
            int base = word << 6;
            long bits = span(0, Math.min(sections, base + Long.SIZE) - base);
            if (faces != null) {
                long seen = 0L;
                for (int part = 0; part < 4; part++) {
                    int at = clearAt + (word << 2) + part;
                    if (at < clearEnd) seen |= (faces[at] & 0xFFFFL) << (part << 4);
                }
                bits &= seen;
            }
            clear[column * words + word] = bits;
        }
    }

    private boolean isClear(int column, int section) {
        return (clear[column * words + (section >>> 6)] >>> section & 1L) != 0L;
    }

    private int low(int column, int section) {
        if (!isClear(column, section)) return section;
        int at = column * words;
        int word = section >>> 6;
        long solid = ~clear[at + word] & ((1L << section) - 1);
        while (solid == 0L) {
            if (word == 0) return 0;
            solid = ~clear[at + --word];
        }
        return (word << 6) + Long.SIZE - Long.numberOfLeadingZeros(solid);
    }

    private int high(int column, int section) {
        if (!isClear(column, section)) return section;
        int at = column * words;
        int word = section >>> 6;
        long solid = ~clear[at + word] & (-1L << section << 1);
        while (solid == 0L) {
            if (++word == words) return sections - 1;
            solid = ~clear[at + word];
        }
        return Math.min(sections, (word << 6) + Long.numberOfTrailingZeros(solid)) - 1;
    }

    private static long span(int from, int to) {
        if (from >= to) return 0L;
        return (to == Long.SIZE ? -1L : (1L << to) - 1) & -1L << from;
    }

    private void mark(int column, int low, int high) {
        for (int word = low >>> 6; word <= high >>> 6; word++) {
            int base = word << 6;
            masks[column * words + word] |= span(Math.max(low, base) - base, Math.min(high + 1, base + Long.SIZE) - base);
        }
    }

    private static double offEdge(double value) {
        double local = value - Math.floor(value / 16.0) * 16.0;
        return local < EDGE ? value + 2 * EDGE : value;
    }

    private void expand(int key) {
        int column = key / sections;
        int low = key % sections;
        int high = high(column, low);
        boolean open = isClear(column, low);
        int gx = column % width;
        int gz = column / width;
        double mx = originX + gx * 16;
        double my = minY + low * 16;
        double mz = originZ + gz * 16;
        double tall = (high - low + 1) * 16;
        int entered = entry[key] & 0xFF;
        boolean start = (entered & START) != 0;
        short[] faces = columns[column];
        int at = low * PER_SECTION;
        int base = slot[key] * FACES * 4;

        for (int exit = 0; exit < FACES; exit++) {
            int nx = gx;
            int nz = gz;
            switch (exit) {
                case DOWN -> { if (low == 0) continue; }
                case UP -> { if (high == sections - 1) continue; }
                case NORTH -> nz--;
                case SOUTH -> nz++;
                case WEST -> nx--;
                default -> nx++;
            }
            if (nx < 0 || nx >= width || nz < 0 || nz >= width) continue;

            boolean far = (exit & 1) == 1;
            double plane;
            double eye;
            double eu;
            double ev;
            double fu;
            double fv;
            double extent;
            switch (exit) {
                case DOWN, UP -> { plane = far ? my + tall : my; eye = eyeY; eu = eyeX; ev = eyeZ; fu = mx; fv = mz; extent = 16; }
                case NORTH, SOUTH -> { plane = far ? mz + 16 : mz; eye = eyeZ; eu = eyeX; ev = eyeY; fu = mx; fv = my; extent = tall; }
                default -> { plane = far ? mx + 16 : mx; eye = eyeX; eu = eyeZ; ev = eyeY; fu = mz; fv = my; extent = tall; }
            }
            if (far ? eye >= plane : eye <= plane) continue;

            double ou0 = FAR;
            double ou1 = -FAR;
            double ov0 = FAR;
            double ov1 = -FAR;
            for (int from = start ? exit : 0; from < FACES; from++) {
                if (!start && ((entered & 1 << from) == 0 || from == exit)) continue;
                double cu0 = fu;
                double cu1 = fu + 16;
                double cv0 = fv;
                double cv1 = fv + extent;
                if (!open) {
                    short portal = faces[at + from * FACES + exit];
                    if (portal == EMPTY) {
                        if (start) break;
                        continue;
                    }
                    cu0 = Math.max(fu, fu + (portal & 15) - MARGIN);
                    cu1 = Math.min(fu + 16, fu + (portal >> 4 & 15) + 1 + MARGIN);
                    cv0 = Math.max(fv, fv + (portal >> 8 & 15) - MARGIN);
                    cv1 = Math.min(fv + 16, fv + (portal >> 12 & 15) + 1 + MARGIN);
                }
                if (start) {
                    ou0 = cu0;
                    ou1 = cu1;
                    ov0 = cv0;
                    ov1 = cv1;
                    break;
                }

                int k = base + (from << 2);
                double a0 = image[k] * UNIT;
                double a1 = image[k + 1] * UNIT;
                double c0 = image[k + 2] * UNIT;
                double c1 = image[k + 3] * UNIT;
                double x0;
                double x1;
                double y0;
                double y1;
                double z0;
                double z1;
                switch (from) {
                    case DOWN, UP -> {
                        y0 = y1 = from == UP ? my + tall : my;
                        x0 = mx + a0; x1 = mx + a1; z0 = mz + c0; z1 = mz + c1;
                    }
                    case NORTH, SOUTH -> {
                        z0 = z1 = from == SOUTH ? mz + 16 : mz;
                        x0 = mx + a0; x1 = mx + a1; y0 = my + c0; y1 = my + c1;
                    }
                    default -> {
                        x0 = x1 = from == EAST ? mx + 16 : mx;
                        z0 = mz + a0; z1 = mz + a1; y0 = my + c0; y1 = my + c1;
                    }
                }
                double near0;
                double near1;
                double u0;
                double u1;
                double v0;
                double v1;
                switch (exit) {
                    case DOWN, UP -> { near0 = y0; near1 = y1; u0 = x0; u1 = x1; v0 = z0; v1 = z1; }
                    case NORTH, SOUTH -> { near0 = z0; near1 = z1; u0 = x0; u1 = x1; v0 = y0; v1 = y1; }
                    default -> { near0 = x0; near1 = x1; u0 = z0; u1 = z1; v0 = y0; v1 = y1; }
                }
                double tMin;
                double tMax;
                if (far) {
                    if (near1 <= eye) continue;
                    double inner = Math.max(near0, eye);
                    tMin = (plane - eye) / (near1 - eye);
                    tMax = inner > eye ? (plane - eye) / (inner - eye) : FAR;
                } else {
                    if (near0 >= eye) continue;
                    double inner = Math.min(near1, eye);
                    tMin = (eye - plane) / (eye - near0);
                    tMax = inner < eye ? (eye - plane) / (eye - inner) : FAR;
                }
                double iu0 = Math.max(cu0, Math.min(eu + tMin * (u0 - eu), eu + tMax * (u0 - eu)));
                double iu1 = Math.min(cu1, Math.max(eu + tMin * (u1 - eu), eu + tMax * (u1 - eu)));
                double iv0 = Math.max(cv0, Math.min(ev + tMin * (v0 - ev), ev + tMax * (v0 - ev)));
                double iv1 = Math.min(cv1, Math.max(ev + tMin * (v1 - ev), ev + tMax * (v1 - ev)));
                if (iu0 > iu1 || iv0 > iv1) continue;
                ou0 = Math.min(ou0, iu0);
                ou1 = Math.max(ou1, iu1);
                ov0 = Math.min(ov0, iv0);
                ov1 = Math.max(ov1, iv1);
            }
            if (ou0 > ou1) continue;

            if (exit == DOWN || exit == UP) {
                enter(column, exit == UP ? high + 1 : low - 1, exit ^ 1, ou0 - fu, ou1 - fu, ov0 - fv, ov1 - fv);
                continue;
            }
            int next = nz * width + nx;
            int section = Math.max(low, (int) Math.floor((ov0 - minY) / 16));
            int last = Math.min(high, (int) Math.floor((ov1 - minY) / 16));
            while (section <= last) {
                int bottom = minY + low(next, section) * 16;
                int top = minY + (high(next, section) + 1) * 16;
                enter(next, section, exit ^ 1, ou0 - fu, ou1 - fu, Math.max(ov0, bottom) - bottom, Math.min(ov1, top) - bottom);
                section = (top - minY) >> 4;
            }
        }
    }

    private static int step(double value, int limit) {
        return (int) Math.max(0, Math.min(limit, value));
    }

    private void claim(int key, int column, int low, int high) {
        if ((slots + 1) * FACES * 4 > image.length) image = Arrays.copyOf(image, Math.max((slots + 1) * FACES * 4, image.length * 2));
        slot[key] = slots++;
        mark(column, low, high);
    }

    private void enter(int column, int section, int face, double u0, double u1, double v0, double v1) {
        int low = low(column, section);
        int high = high(column, low);
        int key = column * sections + low;
        int limit = face == DOWN || face == UP ? SIDE : (high - low + 1) * SIDE;
        int a0 = step(Math.floor(u0 * STEPS), SIDE);
        int a1 = step(Math.ceil(u1 * STEPS), SIDE);
        int c0 = step(Math.floor(v0 * STEPS), limit);
        int c1 = step(Math.ceil(v1 * STEPS), limit);
        int old = entry[key] & 0xFF;
        if (old == 0) claim(key, column, low, high);
        int k = (slot[key] * FACES + face) << 2;
        int bit = 1 << face;
        if ((old & bit) == 0) {
            entry[key] = (byte) (old | bit);
            image[k] = (char) a0;
            image[k + 1] = (char) a1;
            image[k + 2] = (char) c0;
            image[k + 3] = (char) c1;
        } else {
            boolean grew = false;
            if (a0 < image[k]) { image[k] = (char) a0; grew = true; }
            if (a1 > image[k + 1]) { image[k + 1] = (char) a1; grew = true; }
            if (c0 < image[k + 2]) { image[k + 2] = (char) c0; grew = true; }
            if (c1 > image[k + 3]) { image[k + 3] = (char) c1; grew = true; }
            if (!grew) return;
        }
        if ((queued[key >>> 6] & 1L << key) == 0L) push(key);
    }

    private void push(int key) {
        queued[key >>> 6] |= 1L << key;
        queue[tail] = key;
        tail = tail + 1 == nodes ? 0 : tail + 1;
        pending++;
    }
}
