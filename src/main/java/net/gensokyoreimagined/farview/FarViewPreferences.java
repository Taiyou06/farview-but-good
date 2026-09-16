package net.gensokyoreimagined.farview;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

final class FarViewPreferences {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final TypeToken<Map<UUID, Prefs>> TYPE = new TypeToken<>() {};

    private record Prefs(boolean disabled, Integer distance, boolean manualRate, Integer rateCapKbps) {
        static final Prefs DEFAULT = new Prefs(false, null, false, null);
    }

    private final Path file;
    private final ConcurrentHashMap<UUID, Prefs> prefs = new ConcurrentHashMap<>();

    FarViewPreferences(Path file) {
        this.file = file;
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
        prefs.compute(id, (k, p) -> change.apply(p == null ? Prefs.DEFAULT : p));
        save();
    }

    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<UUID, Prefs> loaded = GSON.fromJson(Files.readString(file), TYPE);
            if (loaded != null) prefs.putAll(loaded);
        } catch (IOException | RuntimeException ignored) {}
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(prefs, TYPE.getType()));
        } catch (IOException ignored) {}
    }
}
