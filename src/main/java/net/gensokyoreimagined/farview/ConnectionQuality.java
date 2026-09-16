package net.gensokyoreimagined.farview;

import io.netty.channel.Channel;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** RTT fields are event-loop only; counters crossed by the IO pool are atomic; {@link #snapshot()} is the only main-thread read. */
final class ConnectionQuality {

    public record Snapshot(int pingMs, int jitterMs, int sendRateKbps, int budgetKbps,
                           boolean auto, boolean congested) {}

    private static final double RTT_WEIGHT = 1.0 / 8.0;
    private static final double JITTER_WEIGHT = 1.0 / 16.0;
    private static final double RATE_WEIGHT = 0.3;
    private static final double CHUNK_SIZE_WEIGHT = 0.2;

    private static final double DECREASE_UNSTABLE = 0.8;

    private static final double STARTUP_GAIN_PER_SECOND = 2.0;
    private static final double PROBE_GAIN = 1.25;
    private static final double DRAIN_GAIN = 0.85;

    private static final long BW_BUCKET_NANOS = 2_000_000_000L;
    private static final long PROBE_INTERVAL_NANOS = 2_500_000_000L;
    private static final long PROBE_DURATION_NANOS = 500_000_000L;

    private static final long SPIKE_HOLD_NANOS = 3_000_000_000L;

    private static final double SPIKE_FLOOR_MS = 20.0;

    private static final double MAX_ELAPSED_SECONDS = 0.1;
    private static final double MAX_DEBT_SECONDS = 2.0;

    private static final double INITIAL_WIRE_BYTES_PER_CHUNK = 12 * 1024;
    private static final long KB = 1024L;

    private final Channel channel;
    private volatile FarViewSettings.RatePolicy policy;

    private double rttMs;
    private double jitterMs;
    private double lastSampleMs = -1.0;
    private long spikeUntilNanos;

    private double sendRateBytesPerSec;
    private long lastRateNanos;

    private volatile double budgetBytesPerSec;
    private final AtomicLong tokenBytes = new AtomicLong();
    private final AtomicLong heldBytes = new AtomicLong();
    private long lastTickNanos;

    private boolean startup = true;
    private boolean wasCongested;
    private boolean wasUnstable;
    private boolean tickLimited;
    private boolean windowLimited;
    private boolean windowCongested;
    private double bwBucketCur;
    private double bwBucketPrev;
    private long bwRotateNanos;
    private long probeCycleNanos;

    private final AtomicLong wireWindowBytes = new AtomicLong();
    private final AtomicInteger chunkWindowCount = new AtomicInteger();
    private volatile double wireBytesPerChunk = INITIAL_WIRE_BYTES_PER_CHUNK;

    /** Ping of -1 means no round-trip sample has landed yet; a local connection reads 0ms. */
    private volatile Snapshot snapshot = new Snapshot(-1, 0, 0, 0, true, false);

    public ConnectionQuality(Channel channel, FarViewSettings.RatePolicy policy) {
        this.channel = channel;
        this.policy = policy;
        this.budgetBytesPerSec = policy.defaultKbps() * (double) KB;
        long now = System.nanoTime();
        this.lastRateNanos = now;
        this.lastTickNanos = now;
        this.bwRotateNanos = now;
        this.probeCycleNanos = now;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public void onRtt(long sampleMs) {
        if (lastSampleMs < 0) {
            rttMs = sampleMs;
            lastSampleMs = sampleMs;
            return;
        }
        jitterMs += (Math.abs(sampleMs - lastSampleMs) - jitterMs) * JITTER_WEIGHT;

        if (sampleMs > rttMs * policy.spikeMultiplier() && sampleMs > rttMs + SPIKE_FLOOR_MS) {
            spikeUntilNanos = System.nanoTime() + SPIKE_HOLD_NANOS;
        }

        rttMs += (sampleMs - rttMs) * RTT_WEIGHT;
        lastSampleMs = sampleMs;
    }

    public void onWireBytes(int bytes) {
        wireWindowBytes.addAndGet(bytes);
        long floor = -(long) (budgetBytesPerSec * MAX_DEBT_SECONDS);
        long after = tokenBytes.addAndGet(-bytes);
        if (after < floor) tokenBytes.compareAndSet(after, floor);
    }

    public void onChunkSent() {
        chunkWindowCount.incrementAndGet();
    }

    public int hold() {
        int estimate = (int) Math.max(1.0, wireBytesPerChunk);
        heldBytes.addAndGet(estimate);
        return estimate;
    }

    public void release(int bytes) {
        heldBytes.addAndGet(-bytes);
    }

    public boolean hasTokens() {
        boolean has = tokenBytes.get() - heldBytes.get() > 0;
        if (!has) {
            tickLimited = true;
            windowLimited = true;
        }
        return has;
    }

    public void tick(FarViewSettings.RatePolicy currentPolicy, boolean auto, int capKbps) {
        this.policy = currentPolicy;

        long now = System.nanoTime();
        double elapsed = Math.min((now - lastTickNanos) / 1_000_000_000.0, MAX_ELAPSED_SECONDS);
        lastTickNanos = now;
        if (elapsed <= 0) return;

        boolean congested = !channel.isWritable();
        double capBytes = capKbps * (double) KB;
        double minBytes = currentPolicy.minKbps() * (double) KB;
        boolean unstable = now < spikeUntilNanos || jitterMs > currentPolicy.jitterThresholdMs();
        boolean demand = tickLimited;
        tickLimited = false;
        windowCongested |= congested;

        updateRates(now);

        if (!auto) {
            budgetBytesPerSec = capBytes;
        } else if (congested) {
            if (!wasCongested) {
                startup = false;
                double reference = Math.max(bandwidthEstimate(), sendRateBytesPerSec);
                budgetBytesPerSec = Math.min(budgetBytesPerSec,
                    Math.max(minBytes, reference * DRAIN_GAIN));
                clampModel(budgetBytesPerSec);
            }
        } else if (unstable) {
            if (!wasUnstable) {
                startup = false;
                budgetBytesPerSec *= DECREASE_UNSTABLE;
            }
        } else if (startup) {
            if (demand) budgetBytesPerSec *= Math.pow(STARTUP_GAIN_PER_SECOND, elapsed);
        } else {
            double model = bandwidthEstimate();
            if (model > 0) {
                budgetBytesPerSec = model * (probing(now) ? PROBE_GAIN : 1.0);
            } else if (demand) {
                budgetBytesPerSec += currentPolicy.increaseKbpsPerSecond() * KB * elapsed;
            }
        }
        wasCongested = congested;
        wasUnstable = unstable;
        budgetBytesPerSec = Math.max(minBytes, Math.min(capBytes, budgetBytesPerSec));

        long refill = (long) (budgetBytesPerSec * elapsed);
        long burst = Math.max(refill, 1);
        if (congested) {
            tokenBytes.set(0);
        } else {
            tokenBytes.updateAndGet(current -> Math.min(current + refill, burst));
        }

        snapshot = new Snapshot(
            lastSampleMs < 0 ? -1 : (int) Math.round(rttMs),
            (int) Math.round(jitterMs),
            (int) Math.round(sendRateBytesPerSec / KB),
            (int) Math.round(budgetBytesPerSec / KB),
            auto,
            congested);
    }

    private void updateRates(long now) {
        double elapsed = (now - lastRateNanos) / 1_000_000_000.0;
        if (elapsed < 0.25) return;
        lastRateNanos = now;

        long wire = wireWindowBytes.getAndSet(0);
        int chunks = chunkWindowCount.getAndSet(0);
        double sample = wire / elapsed;

        sendRateBytesPerSec += (sample - sendRateBytesPerSec) * RATE_WEIGHT;

        if (chunks > 0) {
            wireBytesPerChunk += ((double) wire / chunks - wireBytesPerChunk) * CHUNK_SIZE_WEIGHT;
        }

        // BBR app-limited rule: a window that never ran dry, or was frozen by congestion, did not measure the link.
        boolean qualifies = windowLimited && !windowCongested;
        windowLimited = false;
        windowCongested = false;
        if (!qualifies && sample <= bandwidthEstimate()) return;

        if (now - bwRotateNanos >= BW_BUCKET_NANOS) {
            bwBucketPrev = bwBucketCur;
            bwBucketCur = 0.0;
            bwRotateNanos = now;
        }
        bwBucketCur = Math.max(bwBucketCur, sample);
    }

    private double bandwidthEstimate() {
        return Math.max(bwBucketCur, bwBucketPrev);
    }

    private void clampModel(double ceiling) {
        bwBucketCur = Math.min(bwBucketCur, ceiling);
        bwBucketPrev = Math.min(bwBucketPrev, ceiling);
    }

    private boolean probing(long now) {
        if (now - probeCycleNanos >= PROBE_INTERVAL_NANOS) probeCycleNanos = now;
        return now - probeCycleNanos < PROBE_DURATION_NANOS;
    }
}
