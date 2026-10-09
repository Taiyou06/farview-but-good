package net.gensokyoreimagined.farview;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.gensokyoreimagined.farview.nms.PreparedChunk;
import net.gensokyoreimagined.farview.nms.SessionHooks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class FarViewSession implements SessionHooks {
    private static final Map<Integer, long[]> SPIRALS = new ConcurrentHashMap<>();

    private static final double RETRACE_DISTANCE_SQ = 1.0;
    private static final long RETRACE_NANOS = 100_000_000L;
    private static final long[] ALL_SECTIONS = {-1L, -1L, -1L, -1L};
    private static final int MAX_WIDTH = FarViewSettings.CLIENT_MAX_VIEW_DISTANCE * 2 + 1;

    private final Player player;
    private final Channel channel;
    private final FarViewPlugin plugin;

    private volatile ChannelHandlerContext ctx;
    private volatile NamespacedKey dimension;
    private volatile boolean active;
    private volatile int fakeRadius;
    private volatile int serverRadius;
    private volatile boolean autoRate = true;
    private volatile int rateCapKbps;

    private final ConnectionQuality quality;
    private final Long2LongOpenHashMap pendingKeepAlives = new Long2LongOpenHashMap();

    private final AtomicInteger inFlight = new AtomicInteger();

    private final LongOpenHashSet serverChunks = new LongOpenHashSet();
    private final LongOpenHashSet attempted = new LongOpenHashSet();
    private final Long2ObjectOpenHashMap<long[]> sent = new Long2ObjectOpenHashMap<>();
    private final Long2ObjectOpenHashMap<short[]> faces = new Long2ObjectOpenHashMap<>();
    private final Long2IntOpenHashMap ready = new Long2IntOpenHashMap();
    private final LongOpenHashSet resend = new LongOpenHashSet();
    private final LongOpenHashSet building = new LongOpenHashSet();
    private final LongArrayList pending = new LongArrayList();
    private int cursor;

    private boolean culling;
    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private boolean tracing;
    private int generation;
    private int tracedGeneration = -1;
    private long tracedAt;
    private double tracedX;
    private double tracedY;
    private double tracedZ;
    private int tracedCenterX;
    private int tracedCenterZ;
    private int tracedRadius;
    private int tracedWords;
    private long[] visible = new long[MAX_WIDTH * MAX_WIDTH];
    private long[] spare = new long[MAX_WIDTH * MAX_WIDTH];
    private final short[][] snapshot = new short[MAX_WIDTH * MAX_WIDTH][];

    private int centerX;
    private int centerZ;
    private boolean centerKnown;
    private boolean needsRebuild;

    FarViewSession(Player player, Channel channel, FarViewPlugin plugin,
                          NamespacedKey dimension, int serverRadius,
                          FarViewSettings.RatePolicy ratePolicy) {
        this.player = player;
        this.channel = channel;
        this.plugin = plugin;
        this.dimension = dimension;
        this.serverRadius = serverRadius;
        this.quality = new ConnectionQuality(channel, ratePolicy);
        this.rateCapKbps = ratePolicy.maxKbps();
        Location eye = player.getEyeLocation();
        this.eyeX = eye.getX();
        this.eyeY = eye.getY();
        this.eyeZ = eye.getZ();
    }

    NamespacedKey dimension() { return dimension; }
    ConnectionQuality quality() { return quality; }

    public void applyRate(boolean auto, int capKbps) {
        this.autoRate = auto;
        this.rateCapKbps = capKbps;
    }

    public void onKeepAliveSent(long id) {
        if (pendingKeepAlives.size() > 8) pendingKeepAlives.clear();
        pendingKeepAlives.put(id, System.nanoTime());
    }

    public void onKeepAliveResponse(long id) {
        long sentAt = pendingKeepAlives.remove(id);
        if (sentAt == 0L) return;
        quality.onRtt((System.nanoTime() - sentAt) / 1_000_000L);
    }

    void onEye(Location eye) {
        double x = eye.getX();
        double y = eye.getY();
        double z = eye.getZ();
        execute(() -> {
            eyeX = x;
            eyeY = y;
            eyeZ = z;
        });
    }

    public void attach(ChannelHandlerContext ctx) { this.ctx = ctx; }

    public void execute(Runnable task) {
        if (channel.isActive()) channel.eventLoop().execute(task);
    }

    public void applyRadius(int radius) {
        execute(() -> {
            fakeRadius = Math.max(0, radius);
            active = fakeRadius > 0;
            forgetFarChunks(false);
            resetTracking();
            send(plugin.nms().chunkRadiusPacket(effectiveRadius()));
        });
    }

    public int effectiveRadius() {
        return active ? Math.max(fakeRadius, serverRadius) : serverRadius;
    }

    public void onCenter(int chunkX, int chunkZ) {
        if (centerKnown && outsideRing(chunkX, chunkZ)) resetTracking();
        centerX = chunkX;
        centerZ = chunkZ;
        centerKnown = true;
        needsRebuild = true;
    }

    public void onServerRadius(int radius) {
        serverRadius = radius;
        needsRebuild = true;
    }

    public void onServerChunk(int chunkX, int chunkZ) {
        long key = Chunk.getChunkKey(chunkX, chunkZ);
        serverChunks.add(key);
        attempted.remove(key);
        sent.remove(key);
        ready.remove(key);
        resend.remove(key);
    }

    public boolean onForget(int chunkX, int chunkZ) {
        long key = Chunk.getChunkKey(chunkX, chunkZ);
        serverChunks.remove(key);
        if (!active || !centerKnown) return false;
        if (outsideRing(chunkX, chunkZ)) {
            attempted.remove(key);
            return false;
        }
        attempted.add(key);
        sent.put(key, ALL_SECTIONS);
        return true;
    }

    public void onRespawn(NamespacedKey newDimension) {
        dimension = newDimension;
        forgetFarChunks(true);
        resetTracking();
        faces.clear();
        tracedGeneration = -1;
        plugin.reapply(player);
    }

    public void pump(FarViewSettings settings) {
        quality.tick(settings.rate(), autoRate, rateCapKbps);

        if (!active || !centerKnown) return;
        culling = settings.sectionCulling();

        if (needsRebuild) rebuild();

        int reads = Math.min(settings.chunksPerTick(), settings.maxInFlight() - inFlight.get() - ready.size());
        while (reads > 0 && cursor < pending.size()) {
            long key = pending.getLong(cursor++);
            if ((!culling && serverChunks.contains(key)) || !attempted.add(key)) continue;
            inFlight.incrementAndGet();
            plugin.submitRead(this, key);
            reads--;
        }

        if (culling) trace();

        int builds = Math.min(settings.chunksPerTick(), settings.maxInFlight() - inFlight.get());
        LongIterator again = resend.iterator();
        while (builds > 0 && again.hasNext() && quality.hasTokens()) {
            long key = again.nextLong();
            if (building.contains(key)) continue;
            again.remove();
            if (!sent.containsKey(key)) continue;
            build(key, realSections(key, sent.get(key)));
            builds--;
        }

        ObjectIterator<Long2IntMap.Entry> waiting = ready.long2IntEntrySet().fastIterator();
        while (builds > 0 && waiting.hasNext() && quality.hasTokens()) {
            Long2IntMap.Entry entry = waiting.next();
            long key = entry.getLongKey();
            if (building.contains(key)) continue;
            if (culling && !tracedSince(key, entry.getIntValue())) continue;
            waiting.remove();
            build(key, realSections(key, null));
            builds--;
        }
    }

    private void build(long key, long[] realSections) {
        building.add(key);
        inFlight.incrementAndGet();
        plugin.submitBuild(this, key, realSections, quality.hold());
    }

    private void trace() {
        if (tracing) return;
        long now = System.nanoTime();
        if (tracedGeneration >= 0 && now - tracedAt < RETRACE_NANOS) return;
        double dx = eyeX - tracedX;
        double dy = eyeY - tracedY;
        double dz = eyeZ - tracedZ;
        boolean moved = tracedGeneration < 0 || dx * dx + dy * dy + dz * dz >= RETRACE_DISTANCE_SQ
            || centerX != tracedCenterX || centerZ != tracedCenterZ || fakeRadius != tracedRadius;
        if (!moved && generation == tracedGeneration) return;

        int radius = fakeRadius;
        int width = radius * 2 + 1;
        for (int gz = 0; gz < width; gz++) {
            for (int gx = 0; gx < width; gx++) {
                snapshot[gz * width + gx] = faces.get(Chunk.getChunkKey(centerX - radius + gx, centerZ - radius + gz));
            }
        }
        tracedAt = now;
        tracing = plugin.submitTrace(this, eyeX, eyeY, eyeZ, centerX, centerZ, radius, generation, snapshot, spare);
    }

    void traced(double x, double y, double z, int traceCenterX, int traceCenterZ, int radius, int words,
                int traceGeneration, long[] masks) {
        execute(() -> {
            tracing = false;
            spare = visible;
            visible = masks;
            tracedX = x;
            tracedY = y;
            tracedZ = z;
            tracedCenterX = traceCenterX;
            tracedCenterZ = traceCenterZ;
            tracedRadius = radius;
            tracedWords = words;
            tracedGeneration = traceGeneration;
            ObjectIterator<Long2ObjectMap.Entry<long[]>> it = sent.long2ObjectEntrySet().fastIterator();
            while (it.hasNext()) {
                Long2ObjectMap.Entry<long[]> entry = it.next();
                if (reveals(entry.getLongKey(), entry.getValue())) resend.add(entry.getLongKey());
            }
        });
    }

    private boolean tracedSince(long key, int readGeneration) {
        if (tracedGeneration < readGeneration) return false;
        int gx = chunkX(key) - tracedCenterX + tracedRadius;
        int gz = chunkZ(key) - tracedCenterZ + tracedRadius;
        int width = tracedRadius * 2 + 1;
        return gx >= 0 && gx < width && gz >= 0 && gz < width;
    }

    private long[] realSections(long key, long[] base) {
        long[] mask = new long[ALL_SECTIONS.length];
        int at = visibleAt(key);
        for (int word = 0; word < mask.length; word++) {
            mask[word] = seen(at, word) | (base == null ? 0L : base[word]);
        }
        return mask;
    }

    private boolean reveals(long key, long[] have) {
        int at = visibleAt(key);
        for (int word = 0; word < have.length; word++) {
            if ((seen(at, word) & ~have[word]) != 0L) return true;
        }
        return false;
    }

    private long seen(int at, int word) {
        if (at < 0) return -1L;
        return word < tracedWords ? visible[at + word] : 0L;
    }

    private int visibleAt(long key) {
        if (!culling || tracedGeneration < 0 || tracedWords == 0) return -1;
        int width = tracedRadius * 2 + 1;
        int gx = chunkX(key) - tracedCenterX + tracedRadius;
        int gz = chunkZ(key) - tracedCenterZ + tracedRadius;
        if (gx < 0 || gx >= width || gz < 0 || gz >= width) return -1;
        return (gz * width + gx) * tracedWords;
    }

    void chunkRead(long key, PreparedChunk prepared) {
        execute(() -> {
            if (prepared == null || outsideRing(chunkX(key), chunkZ(key))) return;
            if (prepared.faces() != null) {
                faces.put(key, prepared.faces());
                generation++;
            }
            if (serverChunks.contains(key) || serverSends(chunkX(key), chunkZ(key)) || sent.containsKey(key)) return;
            ready.put(key, generation);
        });
    }

    void readFinished() {
        inFlight.decrementAndGet();
    }

    void buildFinished(long key, int held) {
        quality.release(held);
        inFlight.decrementAndGet();
        execute(() -> building.remove(key));
    }

    public void send(Object packet) {
        ChannelHandlerContext current = ctx;
        if (current == null || !channel.isActive()) return;
        current.writeAndFlush(packet, current.voidPromise());
    }

    void sendChunk(long key, Object packet, long[] realSections) {
        execute(() -> {
            building.remove(key);
            if (ctx == null || !active || serverChunks.contains(key)
                || outsideRing(chunkX(key), chunkZ(key))) return;
            long[] had = sent.get(key);
            if (had != null) {
                for (int word = 0; word < realSections.length; word++) realSections[word] |= had[word];
            }
            sent.put(key, realSections);
            quality.onChunkSent();
            send(packet);
            if (reveals(key, realSections)) resend.add(key);
        });
    }

    private void forgetFarChunks(boolean all) {
        ChannelHandlerContext current = ctx;
        if (current == null || sent.isEmpty()) return;
        boolean wrote = false;
        ObjectIterator<Long2ObjectMap.Entry<long[]>> it = sent.long2ObjectEntrySet().fastIterator();
        while (it.hasNext()) {
            long key = it.next().getLongKey();
            if (!all && !outsideRing(chunkX(key), chunkZ(key))) continue;
            it.remove();
            current.write(plugin.nms().forgetChunkPacket(chunkX(key), chunkZ(key)), current.voidPromise());
            wrote = true;
        }
        if (wrote) current.flush();
    }

    private boolean outsideRing(int chunkX, int chunkZ) {
        return Math.max(Math.abs(chunkX - centerX), Math.abs(chunkZ - centerZ)) > fakeRadius;
    }

    private void resetTracking() {
        serverChunks.clear();
        attempted.clear();
        pending.clear();
        ready.clear();
        resend.clear();
        cursor = 0;
        needsRebuild = true;
    }

    private void rebuild() {
        needsRebuild = false;
        pending.clear();
        cursor = 0;

        pruneOutOfRange(serverChunks);
        pruneOutOfRange(attempted);
        pruneOutOfRange(resend);
        pruneOutOfRange(faces.keySet());
        pruneOutOfRange(ready.keySet());
        forgetFarChunks(false);

        for (long offset : spiral(fakeRadius)) {
            int dx = (int) ((offset >> 20) & 0xFFFFF) - SPIRAL_BIAS;
            int dz = (int) (offset & 0xFFFFF) - SPIRAL_BIAS;
            int chunkX = centerX + dx;
            int chunkZ = centerZ + dz;
            if (!culling && serverSends(chunkX, chunkZ)) continue;
            long key = Chunk.getChunkKey(chunkX, chunkZ);
            if ((!culling && serverChunks.contains(key)) || attempted.contains(key)) continue;
            pending.add(key);
        }
    }

    private boolean serverSends(int chunkX, int chunkZ) {
        int dx = Math.abs(chunkX - centerX);
        int dz = Math.abs(chunkZ - centerZ);
        if (Math.max(dx, dz) > serverRadius + 1) return false;
        long bufferedX = Math.max(0, dx - 2);
        long bufferedZ = Math.max(0, dz - 2);
        return bufferedX * bufferedX + bufferedZ * bufferedZ < (long) serverRadius * serverRadius;
    }

    private void pruneOutOfRange(LongSet set) {
        LongIterator it = set.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
            if (outsideRing(chunkX(key), chunkZ(key))) it.remove();
        }
    }

    static int chunkX(long key) {
        return (int) key;
    }

    static int chunkZ(long key) {
        return (int) (key >>> 32);
    }

    private static final int SPIRAL_BIAS = 512;

    private static long[] spiral(int radius) {
        return SPIRALS.computeIfAbsent(radius, r -> {
            int side = r * 2 + 1;
            long[] offsets = new long[side * side];
            int i = 0;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    long distanceSq = (long) dx * dx + (long) dz * dz;
                    offsets[i++] = (distanceSq << 40)
                        | ((long) (dx + SPIRAL_BIAS) << 20)
                        | (dz + SPIRAL_BIAS);
                }
            }
            java.util.Arrays.sort(offsets);
            return offsets;
        });
    }
}
