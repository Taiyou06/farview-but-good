package net.gensokyoreimagined.farview;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.gensokyoreimagined.farview.nms.SessionHooks;
import org.bukkit.Chunk;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class FarViewSession implements SessionHooks {
    private static final Map<Integer, long[]> SPIRALS = new ConcurrentHashMap<>();

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
    private final LongOpenHashSet farChunks = new LongOpenHashSet();
    private final LongArrayList pending = new LongArrayList();
    private int cursor;

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
        farChunks.remove(key);
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
        farChunks.add(key);
        return true;
    }

    public void onRespawn(NamespacedKey newDimension) {
        dimension = newDimension;
        forgetFarChunks(true);
        resetTracking();
        plugin.reapply(player);
    }

    public void pump(FarViewSettings settings) {
        quality.tick(settings.rate(), autoRate, rateCapKbps);

        if (!active || !centerKnown) return;

        if (needsRebuild) rebuild();

        int budget = Math.min(settings.chunksPerTick(), settings.maxInFlight() - inFlight.get());
        while (budget > 0 && quality.hasTokens() && cursor < pending.size()) {
            long key = pending.getLong(cursor++);
            if (serverChunks.contains(key) || !attempted.add(key)) continue;
            inFlight.incrementAndGet();
            plugin.submitRead(this, key, quality.hold());
            budget--;
        }
    }

    void readFinished(int held) {
        quality.release(held);
        inFlight.decrementAndGet();
    }

    public void send(Object packet) {
        ChannelHandlerContext current = ctx;
        if (current == null || !channel.isActive()) return;
        current.writeAndFlush(packet, current.voidPromise());
    }

    void sendChunk(long key, Object packet) {
        execute(() -> {
            if (ctx == null || !active || serverChunks.contains(key)
                || outsideRing(chunkX(key), chunkZ(key))) return;
            farChunks.add(key);
            quality.onChunkSent();
            send(packet);
        });
    }

    private void forgetFarChunks(boolean all) {
        ChannelHandlerContext current = ctx;
        if (current == null || farChunks.isEmpty()) return;
        boolean wrote = false;
        LongIterator it = farChunks.iterator();
        while (it.hasNext()) {
            long key = it.nextLong();
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
        cursor = 0;
        needsRebuild = true;
    }

    private void rebuild() {
        needsRebuild = false;
        pending.clear();
        cursor = 0;

        pruneOutOfRange(serverChunks);
        pruneOutOfRange(attempted);
        forgetFarChunks(false);

        for (long offset : spiral(fakeRadius)) {
            int dx = (int) ((offset >> 20) & 0xFFFFF) - SPIRAL_BIAS;
            int dz = (int) (offset & 0xFFFFF) - SPIRAL_BIAS;
            int chunkX = centerX + dx;
            int chunkZ = centerZ + dz;
            if (serverSends(chunkX, chunkZ)) continue;
            long key = Chunk.getChunkKey(chunkX, chunkZ);
            if (serverChunks.contains(key) || attempted.contains(key)) continue;
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

    private void pruneOutOfRange(LongOpenHashSet set) {
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
