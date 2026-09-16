package net.gensokyoreimagined.farview;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.incendo.cloud.Command;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.execution.CommandExecutionHandler;
import org.incendo.cloud.paper.PaperCommandManager;
import org.incendo.cloud.parser.standard.IntegerParser;
import org.incendo.cloud.suggestion.Suggestion;
import org.incendo.cloud.suggestion.SuggestionProvider;

import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;

final class FarViewCommands {

    private FarViewCommands() {}

    static void register(FarViewPlugin plugin, PaperCommandManager<CommandSourceStack> cmd) {
        Command.Builder<CommandSourceStack> root = cmd.commandBuilder("farview", "fv")
            .permission("farview.use");

        cmd.command(root
            .literal("status")
            .handler(player(plugin, (player, ctx) -> status(plugin, player))));

        cmd.command(root
            .literal("on")
            .handler(player(plugin, (player, ctx) -> {
                plugin.preferences().setEnabled(player.getUniqueId(), true);
                plugin.reapply(player.getUniqueId());
                player.sendMessage(Component.text("Extended view distance on.", NamedTextColor.GREEN));
            })));

        cmd.command(root
            .literal("off")
            .handler(player(plugin, (player, ctx) -> {
                plugin.preferences().setEnabled(player.getUniqueId(), false);
                plugin.reapply(player.getUniqueId());
                player.sendMessage(Component.text("Extended view distance off.", NamedTextColor.YELLOW));
            })));

        cmd.command(root
            .literal("distance")
            .required("chunks", IntegerParser.integerParser(
                FarViewSettings.MIN_VIEW_DISTANCE, FarViewSettings.CLIENT_MAX_VIEW_DISTANCE))
            .handler(player(plugin, (player, ctx) -> {
                int distance = plugin.settings().clampViewDistance(ctx.get("chunks"));
                plugin.preferences().setDistance(player.getUniqueId(), distance);
                plugin.reapply(player.getUniqueId());
                player.sendMessage(Component.text("View distance set to " + distance + " chunks.", NamedTextColor.GREEN));
            })));

        cmd.command(root
            .literal("rate").literal("auto")
            .handler(player(plugin, (player, ctx) -> {
                plugin.preferences().setAutoRate(player.getUniqueId(), true);
                plugin.reapply(player.getUniqueId());
                player.sendMessage(Component.text("Send rate set to auto.", NamedTextColor.GREEN));
            })));

        cmd.command(root
            .literal("rate")
            .required("kbps", IntegerParser.integerParser(1),
                SuggestionProvider.blocking((ctx, in) -> Arrays.stream(plugin.settings().rate().ladder())
                    .mapToObj(step -> Suggestion.suggestion(Integer.toString(step))).toList()))
            .handler(player(plugin, (player, ctx) -> {
                FarViewSettings.RatePolicy rate = plugin.settings().rate();
                int kbps = rate.nearestLadderKbps(ctx.get("kbps"));
                UUID id = player.getUniqueId();
                plugin.preferences().setAutoRate(id, false);
                plugin.preferences().setRateCapKbps(id, kbps);
                plugin.reapply(id);
                player.sendMessage(Component.text("Send rate pinned to " + formatRate(kbps) + ".", NamedTextColor.GREEN));
            })));

        cmd.command(root
            .literal("reload")
            .permission("farview.reload")
            .handler(ctx -> {
                plugin.reload();
                ctx.sender().getSender().sendMessage(Component.text("farview reloaded.", NamedTextColor.GREEN));
            }));
    }

    private static CommandExecutionHandler<CommandSourceStack> player(
            FarViewPlugin plugin, BiConsumer<Player, CommandContext<CommandSourceStack>> body) {
        return ctx -> {
            CommandSender sender = ctx.sender().getSender();
            if (!(sender instanceof Player player)) {
                sender.sendMessage(Component.text("Players only.", NamedTextColor.RED));
                return;
            }
            if (!plugin.available(player)) {
                player.sendMessage(Component.text("Extended view distance is not available here.", NamedTextColor.RED));
                return;
            }
            body.accept(player, ctx);
        };
    }

    private static void status(FarViewPlugin plugin, Player player) {
        FarViewSettings settings = plugin.settings();
        FarViewPreferences prefs = plugin.preferences();
        FarViewSettings.RatePolicy rate = settings.rate();
        UUID id = player.getUniqueId();
        boolean auto = rate.auto() && prefs.isAutoRate(id);
        player.sendMessage(Component.text("farview: " + (prefs.isEnabled(id) ? "on" : "off")
            + ", distance " + prefs.distance(id, settings.defaultViewDistance())
            + " (" + FarViewSettings.MIN_VIEW_DISTANCE + ".." + settings.maxViewDistance() + ")"
            + ", rate " + (auto ? "auto" : formatRate(rate.nearestLadderKbps(prefs.rateCapKbps(id, rate.defaultKbps())))),
            NamedTextColor.GRAY));
        ConnectionQuality.Snapshot c = plugin.connectionSnapshot(player);
        if (c == null || c.pingMs() < 0) {
            player.sendMessage(Component.text("Measuring your connection...", NamedTextColor.DARK_GRAY));
            return;
        }
        String quality = c.congested() ? "saturated"
            : c.jitterMs() > 60 ? "unstable"
            : c.pingMs() > 200 ? "distant" : "good";
        player.sendMessage(Component.text("Ping " + c.pingMs() + "ms, jitter " + c.jitterMs()
            + "ms, sending " + formatRate(c.sendRateKbps()) + " of " + formatRate(c.budgetKbps())
            + " (" + quality + ")", NamedTextColor.DARK_GRAY));
    }

    private static String formatRate(int kbps) {
        double mbps = kbps * 8192.0 / 1_000_000.0;
        if (mbps < 10.0) return String.format(Locale.ROOT, "%.2f Mbps", mbps);
        if (mbps < 100.0) return String.format(Locale.ROOT, "%.1f Mbps", mbps);
        return String.format(Locale.ROOT, "%.0f Mbps", mbps);
    }
}
