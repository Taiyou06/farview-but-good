package net.gensokyoreimagined.farview;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.entity.Player;

final class FarViewPapiExpansion extends PlaceholderExpansion {
    private final FarViewPlugin plugin;

    FarViewPapiExpansion(FarViewPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public String getIdentifier() { return "farview"; }

    @Override
    public String getAuthor() { return String.join(", ", plugin.getPluginMeta().getAuthors()); }

    @Override
    public String getVersion() { return plugin.getPluginMeta().getVersion(); }

    @Override
    public boolean persist() { return true; }

    @Override
    public String onPlaceholderRequest(Player player, String params) {
        return player == null ? null : FarViewPlaceholders.resolve(plugin, player, params);
    }
}
