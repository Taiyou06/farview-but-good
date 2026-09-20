package net.gensokyoreimagined.farview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;
import java.util.logging.Logger;

final class FarViewPreferences {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Map<UUID, Prefs>> TYPE = new TypeToken<>() {};

    private record Prefs(boolean disabled, Integer distance, boolean manualRate, Integer rateCapKbps) {
        static final Prefs DEFAULT = new Prefs(false, null, false, null);
    }

    private final Path file;
    private final Logger logger;
    private final ConcurrentHashMap<UUID, Prefs> prefs = new ConcurrentHashMap<>();

    FarViewPreferences(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        load();
    }

    boolean isEnabled(UUID id) {
        return !get(id).disabled();
    }

    int distance(UUID id, int fallback) {
        Integer value = get(id).distance();
        return value == null ? fallback : value;
    }

    boolean isAutoRate(UUID id) {
        return !get(id).manualRate();
    }

    int rateCapKbps(UUID id, int fallback) {
        Integer value = get(id).rateCapKbps();
        return value == null ? fallback : value;
    }

    void setEnabled(UUID id, boolean value) {
        update(id, p -> new Prefs(!value, p.distance(), p.manualRate(), p.rateCapKbps()));
    }

    void setDistance(UUID id, int value) {
        update(id, p -> new Prefs(p.disabled(), value, p.manualRate(), p.rateCapKbps()));
    }

    void setAutoRate(UUID id, boolean value) {
        update(id, p -> new Prefs(p.disabled(), p.distance(), !value, p.rateCapKbps()));
    }

    void setRateCapKbps(UUID id, int value) {
        update(id, p -> new Prefs(p.disabled(), p.distance(), p.manualRate(), value));
    }

    private Prefs get(UUID id) {
        return prefs.getOrDefault(id, Prefs.DEFAULT);
    }

    private void update(UUID id, UnaryOperator<Prefs> change) {
        prefs.compute(id, (k, p) -> {
            Prefs next = change.apply(p == null ? Prefs.DEFAULT : p);
            return next.equals(Prefs.DEFAULT) ? null : next;
        });
        save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<UUID, Prefs> loaded = GSON.fromJson(Files.readString(file), TYPE);
            if (loaded != null) prefs.putAll(loaded);
        } catch (IOException | RuntimeException e) {
            Path broken = file.resolveSibling(file.getFileName() + ".broken");
            logger.warning("could not read " + file.getFileName() + ", moving it to "
                + broken.getFileName() + " and starting fresh: " + e);
            try {
                Files.move(file, broken, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException moveFailed) {
                logger.warning("could not move " + file.getFileName() + ": " + moveFailed);
            }
        }
    }

    private synchronized void save() {
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(temp, GSON.toJson(prefs, TYPE.getType()));
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            logger.warning("could not save " + file.getFileName() + ": " + e);
        }
    }
}
