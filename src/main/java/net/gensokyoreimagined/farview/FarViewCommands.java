package net.gensokyoreimagined.farview;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

final class FarViewCommands {
    private FarViewCommands() {}

    static void register(FarViewPlugin plugin) {
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event ->
            event.registrar().register(build(plugin), "Extended view distance", List.of("fv")));
    }

    private static LiteralCommandNode<CommandSourceStack> build(FarViewPlugin plugin) {
        return Commands.literal("farview")
            .requires(source -> source.getSender().hasPermission("farview.use"))
            .then(targeted(Commands.literal("status"), plugin, (player, ctx) -> status(plugin, player, ctx)))
            .then(targeted(Commands.literal("on"), plugin, (player, ctx) -> {
                plugin.preferences().setEnabled(player.getUniqueId(), true);
                plugin.reapply(player);
                reply(ctx, player, "Extended view distance on.", NamedTextColor.GREEN);
            }))
            .then(targeted(Commands.literal("off"), plugin, (player, ctx) -> {
                plugin.preferences().setEnabled(player.getUniqueId(), false);
                plugin.reapply(player);
                reply(ctx, player, "Extended view distance off.", NamedTextColor.YELLOW);
            }))
            .then(Commands.literal("distance")
                .then(targeted(Commands.argument("chunks", IntegerArgumentType.integer(
                    FarViewSettings.MIN_VIEW_DISTANCE, FarViewSettings.CLIENT_MAX_VIEW_DISTANCE)), plugin, (player, ctx) -> {
                    int distance = plugin.settings().clampViewDistance(IntegerArgumentType.getInteger(ctx, "chunks"));
                    plugin.preferences().setDistance(player.getUniqueId(), distance);
                    plugin.reapply(player);
                    reply(ctx, player, "View distance set to " + distance + " chunks.", NamedTextColor.GREEN);
                })))
            .then(Commands.literal("rate")
                .then(targeted(Commands.literal("auto"), plugin, (player, ctx) -> {
                    plugin.preferences().setAutoRate(player.getUniqueId(), true);
                    plugin.reapply(player);
                    reply(ctx, player, "Send rate set to auto.", NamedTextColor.GREEN);
                }))
                .then(targeted(Commands.argument("kbps", IntegerArgumentType.integer(1))
                    .suggests((ctx, builder) -> {
                        for (int step : plugin.settings().rate().ladder()) builder.suggest(step);
                        return builder.buildFuture();
                    }), plugin, (player, ctx) -> {
                    FarViewSettings.RatePolicy rate = plugin.settings().rate();
                    int kbps = rate.nearestLadderKbps(IntegerArgumentType.getInteger(ctx, "kbps"));
                    UUID id = player.getUniqueId();
                    plugin.preferences().setAutoRate(id, false);
                    plugin.preferences().setRateCapKbps(id, kbps);
                    plugin.reapply(player);
                    reply(ctx, player, "Send rate pinned to " + FarViewPlaceholders.formatRate(kbps) + ".", NamedTextColor.GREEN);
                })))
            .then(Commands.literal("reload")
                .requires(source -> source.getSender().hasPermission("farview.reload"))
                .executes(ctx -> {
                    plugin.reload();
                    ctx.getSource().getSender().sendMessage(Component.text("farview reloaded.", NamedTextColor.GREEN));
                    return Command.SINGLE_SUCCESS;
                }))
            .build();
    }

    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T targeted(
            T node, FarViewPlugin plugin, BiConsumer<Player, CommandContext<CommandSourceStack>> body) {
        return node
            .executes(target(plugin, body, false))
            .then(Commands.argument("player", ArgumentTypes.player()).executes(target(plugin, body, true)));
    }

    private static Command<CommandSourceStack> target(
            FarViewPlugin plugin, BiConsumer<Player, CommandContext<CommandSourceStack>> body, boolean other) {
        return ctx -> {
            CommandSender sender = ctx.getSource().getSender();
            Player target;
            if (other) {
                if (!sender.hasPermission("farview.others")) {
                    sender.sendMessage(Component.text("You may only change your own settings.", NamedTextColor.RED));
                    return 0;
                }
                target = ctx.getArgument("player", PlayerSelectorArgumentResolver.class)
                    .resolve(ctx.getSource()).getFirst();
            } else if (sender instanceof Player self) {
                target = self;
            } else {
                sender.sendMessage(Component.text("Name a player.", NamedTextColor.RED));
                return 0;
            }
            body.accept(target, ctx);
            if (!plugin.available(target)) {
                reply(ctx, target, "Extended view distance is not available in this world; "
                    + "settings apply once you are in one where it is.", NamedTextColor.GRAY);
            }
            return Command.SINGLE_SUCCESS;
        };
    }

    private static void reply(CommandContext<CommandSourceStack> ctx, Player target, String text, TextColor color) {
        CommandSender sender = ctx.getSource().getSender();
        sender.sendMessage(Component.text(sender == target ? text : target.getName() + ": " + text, color));
    }

    private static void status(FarViewPlugin plugin, Player player, CommandContext<CommandSourceStack> ctx) {
        FarViewSettings settings = plugin.settings();
        FarViewPreferences prefs = plugin.preferences();
        FarViewSettings.RatePolicy rate = settings.rate();
        UUID id = player.getUniqueId();
        boolean auto = rate.auto() && prefs.isAutoRate(id);
        reply(ctx, player, "farview: " + (prefs.isEnabled(id) ? "on" : "off")
            + ", distance " + prefs.distance(id, settings.defaultViewDistance())
            + " (" + FarViewSettings.MIN_VIEW_DISTANCE + ".." + settings.maxViewDistance() + ")"
            + ", rate " + (auto ? "auto" : FarViewPlaceholders.formatRate(rate.nearestLadderKbps(prefs.rateCapKbps(id, rate.defaultKbps())))),
            NamedTextColor.GRAY);
        ConnectionQuality.Snapshot c = plugin.connectionSnapshot(player);
        if (c == null || c.pingMs() < 0) {
            reply(ctx, player, "Measuring connection...", NamedTextColor.DARK_GRAY);
            return;
        }
        reply(ctx, player, "Ping " + c.pingMs() + "ms, jitter " + c.jitterMs()
            + "ms, sending " + FarViewPlaceholders.formatRate(c.sendRateKbps()) + " of " + FarViewPlaceholders.formatRate(c.budgetKbps())
            + " (" + FarViewPlaceholders.quality(c) + ")", NamedTextColor.DARK_GRAY);
    }
}
