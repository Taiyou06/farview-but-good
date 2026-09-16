package net.gensokyoreimagined.farview;

import com.destroystokyo.paper.event.player.PlayerClientOptionsChangeEvent;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

final class FarViewListener implements Listener {
    private final FarViewPlugin plugin;

    FarViewListener(FarViewPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.attach(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.detach(event.getPlayer());
    }

    @EventHandler
    public void onClientOptions(PlayerClientOptionsChangeEvent event) {
        if (!event.hasViewDistanceChanged()) return;
        plugin.reapply(event.getPlayer());
    }
}
