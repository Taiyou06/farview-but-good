package net.gensokyoreimagined.farview;

import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

final class FarViewPlaceholders {
    private FarViewPlaceholders() {}

    static String resolve(FarViewPlugin plugin, Player player, String key) {
        FarViewSettings settings = plugin.settings();
        FarViewPreferences prefs = plugin.preferences();
        FarViewSettings.RatePolicy rate = settings.rate();
        UUID id = player.getUniqueId();
        boolean auto = rate.auto() && prefs.isAutoRate(id);
        int pinned = rate.nearestLadderKbps(prefs.rateCapKbps(id, rate.defaultKbps()));
        ConnectionQuality.Snapshot c = plugin.connectionSnapshot(player);
        int ping = c == null ? -1 : c.pingMs();
        int jitter = c == null ? 0 : c.jitterMs();
        int sending = c == null ? 0 : c.sendRateKbps();
        int budget = c == null ? 0 : c.budgetKbps();
        return switch (key) {
            case "enabled" -> String.valueOf(prefs.isEnabled(id));
            case "available" -> String.valueOf(plugin.available(player));
            case "distance", "dist" -> String.valueOf(prefs.distance(id, settings.defaultViewDistance()));
            case "client_distance" -> String.valueOf(player.getClientViewDistance());
            case "rate" -> auto ? "auto" : String.valueOf(pinned);
            case "rate_mbps" -> auto ? "auto" : formatRate(pinned);
            case "ping" -> String.valueOf(ping);
            case "jitter" -> String.valueOf(jitter);
            case "sending" -> String.valueOf(sending);
            case "sending_mbps" -> formatRate(sending);
            case "budget" -> String.valueOf(budget);
            case "budget_mbps" -> formatRate(budget);
            case "ratio" -> String.valueOf(budget == 0 ? 0 : 100 * sending / budget);
            case "quality" -> quality(c);
            default -> null;
        };
    }

    static String quality(ConnectionQuality.Snapshot c) {
        if (c == null || c.pingMs() < 0) return "measuring";
        return c.congested() ? "saturated"
            : c.jitterMs() > 60 ? "unstable"
            : c.pingMs() > 200 ? "distant" : "good";
    }

    static String formatRate(int kbps) {
        double mbps = kbps * 8192.0 / 1_000_000.0;
        if (mbps < 10.0) return String.format(Locale.ROOT, "%.2f Mbps", mbps);
        if (mbps < 100.0) return String.format(Locale.ROOT, "%.1f Mbps", mbps);
        return String.format(Locale.ROOT, "%.0f Mbps", mbps);
    }
}
