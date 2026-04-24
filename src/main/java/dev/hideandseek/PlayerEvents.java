package dev.hideandseek;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.inventory.ItemStack;

public final class PlayerEvents implements Listener {
    private final GameManager game;

    public PlayerEvents(GameManager game) {
        this.game = game;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        game.onHunterDeath(event.getEntity());
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (!game.isRunning() || !game.isHider(event.getPlayer()) || !game.isHiderLocked()) {
            return;
        }
        if (event.getTo() == null) return;
        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        game.handleInventoryClick(event);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        ItemStack item = event.getItem();
        if (item == null || item.getType() != Material.COMPASS || !item.hasItemMeta() || !item.getItemMeta().hasDisplayName()) {
            return;
        }
        String name = ChatColor.stripColor(item.getItemMeta().getDisplayName());
        if ("Hide&Seek Menu".equalsIgnoreCase(name)) {
            event.setCancelled(true);
            game.openMainMenu(event.getPlayer());
        }
    }
}
