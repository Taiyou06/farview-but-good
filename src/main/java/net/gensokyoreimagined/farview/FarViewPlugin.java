package net.gensokyoreimagined.farview;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.gensokyoreimagined.farview.packet.FakeChunkCache;
import net.gensokyoreimagined.farview.packet.FakeChunkPacketFactory;
import net.gensokyoreimagined.farview.region.SavedChunk;
import net.gensokyoreimagined.farview.region.WorldRegionSource;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.incendo.cloud.execution.ExecutionCoordinator;
import org.incendo.cloud.paper.PaperCommandManager;
import org.spongepowered.configurate.CommentedConfigurationNode;
import org.spongepowered.configurate.ConfigurateException;
import org.spongepowered.configurate.hocon.HoconConfigurationLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Map<ResourceKey<Level>, WorldRegionSource> sources = new ConcurrentHashMap<>();

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

    @Override
    public void onEnable() {
        this.logger = getLogger();
        Path folder = getDataFolder().toPath();
        try {
            Files.createDirectories(folder);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        this.configFile = folder.resolve("farview.conf");
        this.configLoader = HoconConfigurationLoader.builder().path(configFile).build();
        this.preferences = new FarViewPreferences(folder.resolve("preferences.json"));

        PaperCommandManager<CommandSourceStack> commands = PaperCommandManager.builder()
            .executionCoordinator(ExecutionCoordinator.asyncCoordinator())
            .buildOnEnable(this);
        FarViewCommands.register(this, commands);

        start();
    }

    @Override
    public void onDisable() {
        stop();
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

            for (WorldRegionSource source : sources.values()) source.close();
            sources.clear();

            if (cache != null) { cache.invalidateAll(); cache = null; }
        }
    }

    private void initWorlds() {
        MinecraftServer server = MinecraftServer.getServer();
        for (ServerLevel level : server.getAllLevels()) {
            if (!settings.worlds().contains(level.getWorld().getName())) continue;
            sources.put(level.dimension(), new WorldRegionSource(level, server, settings.regionReaderCache()));
        }
        if (sources.isEmpty()) {
            logger.warning("none of the allowlisted worlds " + settings.worlds()
                + " exist; no fake chunks will be sent");
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().run(this, task -> attach(player), null);
        }
    }

    void attach(Player player) {
        FarViewSession session = FarViewInjector.inject(this, player);
        sessions.put(player.getUniqueId(), session);
        applyPreferences(player, session);
    }

    void detach(Player player) {
        if (sessions.remove(player.getUniqueId()) == null) return;
        FarViewInjector.uninject(player);
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

    void submitRead(FarViewSession session, long chunkKey, int held) {
        ExecutorService pool = ioPool;
        if (pool == null) {
            session.readFinished(held);
            return;
        }
        try {
            pool.execute(() -> runRead(session, chunkKey, held));
        } catch (RejectedExecutionException rejected) {
            session.readFinished(held);
        }
    }

    private void runRead(FarViewSession session, long chunkKey, int held) {
        try {
            WorldRegionSource source = sources.get(session.dimension());
            FakeChunkCache currentCache = cache;
            if (source == null || currentCache == null) return;

            ClientboundLevelChunkWithLightPacket packet =
                currentCache.get(source.dimension(), chunkKey, key -> load(source, key));
            if (packet != null) session.sendChunk(packet);

        } catch (UncheckedIOException io) {
            logger.fine("region read failed for " + ChunkPos.getX(chunkKey)
                + "," + ChunkPos.getZ(chunkKey) + ": " + io.getMessage());
        } catch (Throwable t) {
            logger.warning("could not build chunk " + ChunkPos.getX(chunkKey)
                + "," + ChunkPos.getZ(chunkKey) + ": " + t);
        } finally {
            session.readFinished(held);
        }
    }

    private ClientboundLevelChunkWithLightPacket load(WorldRegionSource source, long chunkKey) {
        int chunkX = ChunkPos.getX(chunkKey);
        int chunkZ = ChunkPos.getZ(chunkKey);
        SavedChunk saved;
        try {
            saved = source.readChunk(chunkX, chunkZ, settings.blockEntities());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        if (saved == null) return skip(chunkX, chunkZ, "not saved to disk");
        if (!saved.full()) return skip(chunkX, chunkZ, "status is not full");
        if (settings.requireSavedLight() && !saved.hasValidLight()) {
            return skip(chunkX, chunkZ, "saved light is missing or stale");
        }

        return FakeChunkPacketFactory.build(source, settings, chunkX, chunkZ, saved);
    }

    private ClientboundLevelChunkWithLightPacket skip(int chunkX, int chunkZ, String reason) {
        if (settings.debug()) {
            logger.info("skipped chunk " + chunkX + "," + chunkZ + ": " + reason);
        }
        return null;
    }
}
