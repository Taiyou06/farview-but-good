package net.gensokyoreimagined.farview;

import net.gensokyoreimagined.farview.nms.ChunkSource;
import net.gensokyoreimagined.farview.nms.FarViewNms;
import net.gensokyoreimagined.farview.nms.OcclusionGraph;
import net.gensokyoreimagined.farview.nms.PreparedChunk;
import net.gensokyoreimagined.farview.packet.FakeChunkCache;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

public final class FarViewPlugin extends JavaPlugin {
    private static final long TICK_MS = 50L;

    private final Map<UUID, FarViewSession> sessions = new ConcurrentHashMap<>();
    private final Map<NamespacedKey, ChunkSource> sources = new ConcurrentHashMap<>();

    private FarViewNms nms;
    private Logger logger;
    private Path configFile;
    private HoconConfigurationLoader configLoader;
    private FarViewSettings settings;
    private FarViewPreferences preferences;
    private FarViewListener listener;
    private FakeChunkCache cache;

    private ScheduledExecutorService scheduler;
    private ExecutorService ioPool;

    FarViewSettings settings() { return settings; }
    FarViewPreferences preferences() { return preferences; }
    FarViewNms nms() { return nms; }

    @Override
    public void onEnable() {
        this.logger = getLogger();
        this.nms = loadNms();
        logger.info("Minecraft " + Bukkit.getMinecraftVersion() + ", using " + nms.getClass().getName());
        Path folder = getDataFolder().toPath();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.configFile = folder.resolve("farview.conf");
        this.configLoader = HoconConfigurationLoader.builder().path(configFile).build();
        this.preferences = new FarViewPreferences(folder.resolve("preferences.json"), logger);

        FarViewCommands.register(this);
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new FarViewPapiExpansion(this).register();
        }
        Plugin mythic = getServer().getPluginManager().getPlugin("MythicMobs");
        if (mythic != null) FarViewMythicPlaceholders.hook(this, mythic);

        start();
    }

    @Override
    public void onDisable() {
        stop();
    }

    private static final Map<String, String> NMS_MODULES = Map.of(
        "1.21.8", "v1_21_8",
        "1.21.11", "v1_21_11",
        "26.1", "v26_1", "26.1.1", "v26_1", "26.1.2", "v26_1",
        "26.2", "v26_2",
        "26.3", "v26_3");

    private static FarViewNms loadNms() {
        String version = Bukkit.getMinecraftVersion();
        String module = NMS_MODULES.get(version);
        if (module == null) throw new IllegalStateException("farview does not support Minecraft " + version);
        try {
            return (FarViewNms) Class.forName("net.gensokyoreimagined.farview.nms." + module + ".FarViewNmsImpl")
                .getConstructor().newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("could not load farview support for Minecraft " + version, e);
        }
    }

    void reload() {
        Bukkit.getGlobalRegionScheduler().run(this, task -> {
            stop();
            start();
        });
    }

    private FarViewSettings loadSettings() {
        try {
            if (!Files.exists(configFile)) {
                configLoader.save(FarViewSettings.seed(configLoader.createNode()));
            }
            return FarViewSettings.load(configLoader.load());
        } catch (ConfigurateException e) {
            throw new RuntimeException("failed to load farview.conf", e);
        }
    }

    private void start() {
        this.settings = loadSettings();
        if (!settings.enabled()) {
            logger.info("disabled in config");
            return;
        }

        this.cache = new FakeChunkCache(settings.cacheMaxBytes(), settings.cacheExpireSeconds());
        this.ioPool = Executors.newFixedThreadPool(settings.ioThreads(),
            Thread.ofPlatform().name("farview-io-", 0).daemon().factory());
        this.scheduler = Executors.newSingleThreadScheduledExecutor(
            Thread.ofPlatform().name("farview-tick").daemon().factory());
        this.scheduler.scheduleAtFixedRate(this::tick, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);

        this.listener = new FarViewListener(this);
        Bukkit.getPluginManager().registerEvents(listener, this);

        Bukkit.getGlobalRegionScheduler().run(this, task -> initWorlds());
    }

    private void stop() {
        if (listener != null) {
            HandlerList.unregisterAll(listener);
            listener = null;
        }
        try {
            for (FarViewSession session : sessions.values()) session.applyRadius(0);
            for (Player player : Bukkit.getOnlinePlayers()) detach(player);
        } finally {
            sessions.clear();

            if (scheduler != null) { scheduler.shutdownNow(); scheduler = null; }
            if (ioPool != null) { ioPool.shutdownNow(); ioPool = null; }

            for (ChunkSource source : sources.values()) source.close();
            sources.clear();

            if (cache != null) { cache.invalidateAll(); cache = null; }
        }
    }

    private void initWorlds() {
        for (World world : Bukkit.getWorlds()) openWorld(world);
        if (sources.isEmpty()) {
            logger.warning("none of the allowlisted worlds " + settings.worlds()
                + " are loaded yet; fake chunks start once one of them loads (loaded worlds: "
                + Bukkit.getWorlds().stream().map(World::getName).toList() + ")");
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, task -> attach(player), null);
        }
    }

    void openWorld(World world) {
        if (!settings.worlds().contains(world.getName().toLowerCase(Locale.ROOT)) || sources.containsKey(world.getKey())) return;
        sources.put(world.getKey(), nms.openWorld(world, settings.regionReaderCache(),
            settings.debug() ? logger::info : null));
    }

    void closeWorld(World world) {
        ChunkSource source = sources.remove(world.getKey());
        if (source != null) source.close();
    }

    void attach(Player player) {
        FarViewSession session = FarViewInjector.inject(this, player);
        sessions.put(player.getUniqueId(), session);
        applyPreferences(player, session);
        player.getScheduler().runAtFixedRate(this, task -> {
            if (sessions.get(player.getUniqueId()) != session) task.cancel();
            else session.onEye(player.getEyeLocation());
        }, null, 1L, 1L);
    }

    void detach(Player player) {
        if (sessions.remove(player.getUniqueId()) == null) return;
        FarViewInjector.uninject(this, player);
    }

    void reapply(Player player) {
        player.getScheduler().run(this, task -> {
            FarViewSession session = sessions.get(player.getUniqueId());
            if (session != null) applyPreferences(player, session);
        }, null);
    }

    ConnectionQuality.Snapshot connectionSnapshot(Player player) {
        FarViewSession session = sessions.get(player.getUniqueId());
        return session == null ? null : session.quality().snapshot();
    }

    private void applyPreferences(Player player, FarViewSession session) {
        UUID id = player.getUniqueId();
        FarViewSettings.RatePolicy rate = settings.rate();
        boolean auto = rate.auto() && preferences.isAutoRate(id);
        session.applyRate(auto, auto
            ? rate.maxKbps()
            : rate.nearestLadderKbps(preferences.rateCapKbps(id, rate.defaultKbps())));
        session.applyRadius(desiredRadius(player));
    }

    private FarViewSession served(Player player) {
        FarViewSession session = sessions.get(player.getUniqueId());
        return session != null && sources.containsKey(session.dimension()) ? session : null;
    }

    boolean available(Player player) {
        return served(player) != null;
    }

    private int desiredRadius(Player player) {
        if (served(player) == null || !preferences.isEnabled(player.getUniqueId())) return 0;
        return clampToClient(player, settings.clampViewDistance(
            preferences.distance(player.getUniqueId(), settings.defaultViewDistance())));
    }

    private static int clampToClient(Player player, int radius) {
        int client = player.getClientViewDistance();
        return client <= 0 ? radius : Math.min(radius, client + 1);
    }

    private void tick() {
        FarViewSettings current = settings;
        if (current == null) return;
        for (FarViewSession session : sessions.values()) {
            session.execute(() -> session.pump(current));
        }
    }

    void submitRead(FarViewSession session, long chunkKey) {
        ExecutorService pool = ioPool;
        if (pool == null) {
            session.readFinished();
            return;
        }
        try {
            pool.execute(() -> runRead(session, chunkKey));
        } catch (RejectedExecutionException rejected) {
            session.readFinished();
        }
    }

    private void runRead(FarViewSession session, long chunkKey) {
        try {
            NamespacedKey dimension = session.dimension();
            ChunkSource source = sources.get(dimension);
            FakeChunkCache currentCache = cache;
            if (source == null || currentCache == null) return;

            session.chunkRead(chunkKey, currentCache.get(dimension, chunkKey, key -> load(source, key)));

        } catch (UncheckedIOException io) {
            logger.fine("region read failed for " + FarViewSession.chunkX(chunkKey)
                + "," + FarViewSession.chunkZ(chunkKey) + ": " + io.getMessage());
        } catch (Throwable t) {
            logger.warning("could not build chunk " + FarViewSession.chunkX(chunkKey)
                + "," + FarViewSession.chunkZ(chunkKey) + ": " + t);
        } finally {
            session.readFinished();
        }
    }

    void submitBuild(FarViewSession session, long chunkKey, long[] realSections, int held) {
        ExecutorService pool = ioPool;
        if (pool == null) {
            session.buildFinished(chunkKey, held);
            return;
        }
        try {
            pool.execute(() -> runBuild(session, chunkKey, realSections, held));
        } catch (RejectedExecutionException rejected) {
            session.buildFinished(chunkKey, held);
        }
    }

    private void runBuild(FarViewSession session, long chunkKey, long[] realSections, int held) {
        try {
            NamespacedKey dimension = session.dimension();
            ChunkSource source = sources.get(dimension);
            FakeChunkCache currentCache = cache;
            if (source == null || currentCache == null) return;

            PreparedChunk chunk = currentCache.get(dimension, chunkKey, key -> load(source, key));
            if (chunk != null) session.sendChunk(chunkKey, source.packet(chunk, realSections), realSections);

        } catch (UncheckedIOException io) {
            logger.fine("region read failed for " + FarViewSession.chunkX(chunkKey)
                + "," + FarViewSession.chunkZ(chunkKey) + ": " + io.getMessage());
        } catch (Throwable t) {
            logger.warning("could not build chunk " + FarViewSession.chunkX(chunkKey)
                + "," + FarViewSession.chunkZ(chunkKey) + ": " + t);
        } finally {
            session.buildFinished(chunkKey, held);
        }
    }

    boolean submitTrace(FarViewSession session, double x, double y, double z, int centerX, int centerZ,
                        int radius, int generation, short[][] columns, long[] masks) {
        ExecutorService pool = ioPool;
        if (pool == null) return false;
        try {
            pool.execute(() -> runTrace(session, x, y, z, centerX, centerZ, radius, generation, columns, masks));
            return true;
        } catch (RejectedExecutionException rejected) {
            return false;
        }
    }

    private void runTrace(FarViewSession session, double x, double y, double z, int centerX, int centerZ,
                          int radius, int generation, short[][] columns, long[] masks) {
        int width = radius * 2 + 1;
        long[] traced = masks;
        int words = 0;
        try {
            ChunkSource source = sources.get(session.dimension());
            if (source != null) {
                int sections = source.sectionCount();
                int needed = width * width * OcclusionGraph.words(sections);
                if (traced.length < needed) traced = new long[needed];
                OcclusionGraph.forThread().trace(x, y, z, centerX, centerZ, radius,
                    source.minSectionY(), sections, settings.includeHeightmapOnOcclusion(), columns, traced);
                words = OcclusionGraph.words(sections);
            }
        } catch (Throwable t) {
            logger.warning("occlusion trace failed, sending every section: " + t);
        }
        session.traced(x, y, z, centerX, centerZ, radius, words, generation, traced);
    }

    private PreparedChunk load(ChunkSource source, long chunkKey) {
        try {
            return source.read(FarViewSession.chunkX(chunkKey), FarViewSession.chunkZ(chunkKey),
                settings.blockEntities(), settings.requireSavedLight(), settings.sectionCulling());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
