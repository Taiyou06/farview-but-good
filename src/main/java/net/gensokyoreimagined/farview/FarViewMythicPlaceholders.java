package net.gensokyoreimagined.farview;

import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicPlaceholdersLoadEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;

final class FarViewMythicPlaceholders implements Listener {
    private FarViewMythicPlaceholders() {}

    static void hook(FarViewPlugin plugin, Plugin mythic) {
        plugin.getServer().getPluginManager().registerEvents(new FarViewMythicPlaceholders(), plugin);
        if (mythic.isEnabled()) MythicBukkit.inst().getPlaceholderManager().register(FarViewMythicPlaceholder.class);
    }

    @EventHandler
    public void onLoad(MythicPlaceholdersLoadEvent event) {
        event.getManager().register(FarViewMythicPlaceholder.class);
    }
}
