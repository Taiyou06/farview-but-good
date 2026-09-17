package net.gensokyoreimagined.farview;

import net.gensokyoreimagined.farview.nms.BlockEntityPolicy;
import org.bukkit.NamespacedKey;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurationNode;
import org.spongepowered.configurate.serialize.SerializationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public record FarViewSettings(boolean enabled, Set<String> worlds, int maxViewDistance, int defaultViewDistance,
                              int chunksPerTick, int maxInFlight, int ioThreads,
                              long cacheMaxBytes, long cacheExpireSeconds, int regionReaderCache,
                              boolean requireSavedLight, boolean debug, RatePolicy rate,
                              BlockEntityPolicy blockEntities) {
    public static final int CLIENT_MAX_VIEW_DISTANCE = 32;
    public static final int MIN_VIEW_DISTANCE = 4;

    public static final class Defaults {
        public static final boolean ENABLED = true;
        public static final List<String> WORLDS = List.of("world");
        public static final int MAX_VIEW_DISTANCE = 32;
        public static final int DEFAULT_VIEW_DISTANCE = 16;
        public static final int CHUNKS_PER_TICK = 64;
        public static final int MAX_IN_FLIGHT = 128;
        public static final int IO_THREADS = 4;
        public static final long CACHE_MAX_BYTES = 67108864L;
        public static final long CACHE_EXPIRE_SECONDS = 300L;
        public static final int REGION_READER_CACHE = 32;
        public static final boolean REQUIRE_SAVED_LIGHT = true;
        public static final boolean DEBUG = false;

        public static final boolean RATE_AUTO = true;
        public static final int RATE_MIN_KBPS = 32;
        public static final int RATE_MAX_KBPS = 12288;
        public static final int RATE_DEFAULT_KBPS = 256;
        public static final int RATE_INCREASE_KBPS_PER_SECOND = 32;
        public static final double RATE_SPIKE_MULTIPLIER = 2.0;
        public static final int RATE_JITTER_THRESHOLD_MS = 60;

        public static final List<Integer> RATE_LADDER_KBPS = List.of(
            32, 48, 64, 96, 128, 192, 256, 384, 512, 768,
            1024, 1536, 2048, 3072, 4096, 5120, 6144, 8192, 10240, 12288);

        public static final boolean BLOCK_ENTITIES_ENABLED = true;
        public static final int BLOCK_ENTITIES_MAX_PER_CHUNK = 64;
        public static final List<String> BLOCK_ENTITY_TYPES = List.of(
            "minecraft:sign", "minecraft:hanging_sign", "minecraft:banner",
            "minecraft:skull", "minecraft:bed", "minecraft:decorated_pot",
            "minecraft:beacon");

        private Defaults() {}
    }

    public record RatePolicy(boolean auto, int minKbps, int maxKbps, int defaultKbps,
                             int increaseKbpsPerSecond, double spikeMultiplier, int jitterThresholdMs,
                             int[] ladder) {
        public int clampKbps(int requested) {
            return Math.clamp(requested, minKbps, maxKbps);
        }

        public int nearestLadderKbps(int requested) {
            int clamped = clampKbps(requested);
            int best = ladder[0];
            for (int step : ladder) {
                if (Math.abs(step - clamped) < Math.abs(best - clamped)) best = step;
            }
            return best;
        }
    }

    public int clampViewDistance(int requested) {
        return Math.clamp(requested, MIN_VIEW_DISTANCE, maxViewDistance);
    }

    public static CommentedConfigurationNode seed(CommentedConfigurationNode node) throws SerializationException {
        CommentedConfigurationNode farview = node.node("farview");
        farview.comment(
            "Sends terrain beyond the server's real view distance by reading region files "
          + "directly and pushing chunk packets over netty. The server keeps its own view "
          + "distance and never loads these chunks.\n"
          + "Limitations: fake chunks are a disk snapshot, so they carry no entities and no "
          + "block updates until the player walks into the real view distance. Only terrain "
          + "that is already generated, saved, and lit is shown. Paper anti-xray does not "
          + "apply to them. Players must also raise their own client render distance.");

        farview.node("enabled").set(Defaults.ENABLED);
        farview.node("worlds").setList(String.class, Defaults.WORLDS)
            .comment("Worlds allowed to serve fake chunks. Keep worlds handled by other plugins "
                   + "that rewrite chunk packets out of this list.");
        farview.node("max-view-distance").set(Defaults.MAX_VIEW_DISTANCE)
            .comment("Upper bound a player may pick. The vanilla client clamps the server-sent "
                   + "radius, so values above 32 are ignored by the client.");
        farview.node("default-view-distance").set(Defaults.DEFAULT_VIEW_DISTANCE)
            .comment("Distance used by a player who enabled the feature but never picked one.");
        farview.node("chunks-per-tick").set(Defaults.CHUNKS_PER_TICK)
            .comment("Safety ceiling on dispatches per player per tick. The rate block below is "
                   + "what normally governs; keep this high enough that it does not, or a "
                   + "player's chosen speed silently stops applying.");
        farview.node("max-in-flight").set(Defaults.MAX_IN_FLIGHT)
            .comment("Outstanding disk reads per player. Must exceed chunks-per-tick or it "
                   + "becomes the real limit instead.");
        farview.node("io-threads").set(Defaults.IO_THREADS)
            .comment("Threads building fake chunks. Region read, decompress and repack cost lands "
                   + "here, so this is what actually caps throughput once the rate block is "
                   + "no longer the tighter limit.");
        farview.node("cache-max-bytes").set(Defaults.CACHE_MAX_BYTES)
            .comment("Built packets shared across players, weighed by payload size. 0 disables.");
        farview.node("cache-expire-seconds").set(Defaults.CACHE_EXPIRE_SECONDS);
        farview.node("region-reader-cache").set(Defaults.REGION_READER_CACHE)
            .comment("Open region files kept per world.");
        farview.node("require-saved-light").set(Defaults.REQUIRE_SAVED_LIGHT)
            .comment("Skip chunks whose saved light is missing or stale instead of sending them black.");
        farview.node("debug").set(Defaults.DEBUG)
            .comment("Log why individual chunks are skipped. Turn this on to diagnose holes in "
                   + "the fake ring, then turn it back off; it is one line per skipped chunk.");

        CommentedConfigurationNode rate = farview.node("rate");
        rate.comment(
            "Per-player send rate. Each player gets a byte budget that adapts to their measured "
          + "connection: round-trip time and jitter are sampled from the keepalives the server "
          + "already sends once a second, and netty's outbound buffer is watched for backpressure.\n"
          + "Players can turn the adaptive part off with /farview rate <kbps> and pin their own "
          + "cap. The backpressure gate always applies regardless, because ignoring it grows "
          + "server memory rather than their own.");
        rate.node("auto").set(Defaults.RATE_AUTO)
            .comment("Server-wide switch for the adaptive part. Off pins everyone to their cap.");
        rate.node("min-kbps").set(Defaults.RATE_MIN_KBPS)
            .comment("Floor the budget never drops below, so a bad connection still makes progress.");
        rate.node("max-kbps").set(Defaults.RATE_MAX_KBPS)
            .comment("Ceiling for both the adaptive climb and the manual ladder. Counts bytes on "
                   + "the wire after compression, and sits under what chunks-per-tick can "
                   + "dispatch so this block stays the thing that governs.");
        rate.node("default-kbps").set(Defaults.RATE_DEFAULT_KBPS)
            .comment("Starting budget for a fresh session, before any measurement has landed.");
        rate.node("increase-kbps-per-second").set(Defaults.RATE_INCREASE_KBPS_PER_SECOND)
            .comment("Fallback climb rate for the brief phase where no delivered rate has been "
                   + "measured yet; once one has, the budget tracks the measurement directly.");
        rate.node("spike-multiplier").set(Defaults.RATE_SPIKE_MULTIPLIER)
            .comment("A round-trip sample this many times the running average counts as a spike "
                   + "and suppresses the budget for a few seconds.");
        rate.node("jitter-threshold-ms").set(Defaults.RATE_JITTER_THRESHOLD_MS)
            .comment("Sustained jitter above this holds the budget down.");
        rate.node("ladder-kbps").setList(Integer.class, Defaults.RATE_LADDER_KBPS)
            .comment("Speeds a player may pick with /farview rate when auto is off; a typed value "
                   + "snaps to the nearest step. Steps outside min-kbps..max-kbps are hidden.");

        CommentedConfigurationNode blockEntities = farview.node("block-entities");
        blockEntities.comment(
            "Region files hold the full save tag, not the client update tag, so an unfiltered "
          + "pass-through would ship container inventories to the client. Only these types are sent.");
        blockEntities.node("enabled").set(Defaults.BLOCK_ENTITIES_ENABLED);
        blockEntities.node("max-per-chunk").set(Defaults.BLOCK_ENTITIES_MAX_PER_CHUNK);
        blockEntities.node("types").setList(String.class, Defaults.BLOCK_ENTITY_TYPES);

        return node;
    }

    public static FarViewSettings load(ConfigurationNode root) throws SerializationException {
        ConfigurationNode node = root.node("farview");
        int max = Math.clamp(node.node("max-view-distance").getInt(Defaults.MAX_VIEW_DISTANCE),
            MIN_VIEW_DISTANCE, CLIENT_MAX_VIEW_DISTANCE);
        int def = Math.clamp(node.node("default-view-distance").getInt(Defaults.DEFAULT_VIEW_DISTANCE),
            MIN_VIEW_DISTANCE, max);

        ConfigurationNode be = node.node("block-entities");
        Set<String> beTypes = new LinkedHashSet<>();
        for (String raw : be.node("types").getList(String.class, Defaults.BLOCK_ENTITY_TYPES)) {
            NamespacedKey key = NamespacedKey.fromString(raw);
            if (key == null) throw new SerializationException("invalid block entity id " + raw);
            beTypes.add(key.toString());
        }

        return new FarViewSettings(
            node.node("enabled").getBoolean(Defaults.ENABLED),
            Set.copyOf(node.node("worlds").getList(String.class, Defaults.WORLDS)),
            max,
            def,
            Math.max(1, node.node("chunks-per-tick").getInt(Defaults.CHUNKS_PER_TICK)),
            Math.max(1, node.node("max-in-flight").getInt(Defaults.MAX_IN_FLIGHT)),
            Math.max(1, node.node("io-threads").getInt(Defaults.IO_THREADS)),
            Math.max(0L, node.node("cache-max-bytes").getLong(Defaults.CACHE_MAX_BYTES)),
            Math.max(1L, node.node("cache-expire-seconds").getLong(Defaults.CACHE_EXPIRE_SECONDS)),
            Math.max(1, node.node("region-reader-cache").getInt(Defaults.REGION_READER_CACHE)),
            node.node("require-saved-light").getBoolean(Defaults.REQUIRE_SAVED_LIGHT),
            node.node("debug").getBoolean(Defaults.DEBUG),
            loadRate(node.node("rate")),
            new BlockEntityPolicy(
                be.node("enabled").getBoolean(Defaults.BLOCK_ENTITIES_ENABLED),
                Math.max(0, be.node("max-per-chunk").getInt(Defaults.BLOCK_ENTITIES_MAX_PER_CHUNK)),
                Set.copyOf(beTypes)));
    }

    private static RatePolicy loadRate(ConfigurationNode node) throws SerializationException {
        int min = Math.max(1, node.node("min-kbps").getInt(Defaults.RATE_MIN_KBPS));
        int max = Math.max(min, node.node("max-kbps").getInt(Defaults.RATE_MAX_KBPS));
        int def = Math.clamp(node.node("default-kbps").getInt(Defaults.RATE_DEFAULT_KBPS), min, max);
        int[] ladder = node.node("ladder-kbps").getList(Integer.class, Defaults.RATE_LADDER_KBPS).stream()
            .mapToInt(Integer::intValue)
            .filter(step -> step >= min && step <= max)
            .sorted()
            .distinct()
            .toArray();
        return new RatePolicy(
            node.node("auto").getBoolean(Defaults.RATE_AUTO),
            min,
            max,
            def,
            Math.max(1, node.node("increase-kbps-per-second").getInt(Defaults.RATE_INCREASE_KBPS_PER_SECOND)),
            Math.max(1.1, node.node("spike-multiplier").getDouble(Defaults.RATE_SPIKE_MULTIPLIER)),
            Math.max(1, node.node("jitter-threshold-ms").getInt(Defaults.RATE_JITTER_THRESHOLD_MS)),
            ladder.length == 0 ? new int[] { def } : ladder);
    }
}
