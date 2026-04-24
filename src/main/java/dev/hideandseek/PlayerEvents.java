package dev.hideandseek;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public final class PlayerEvents implements Listener {
    private final GameManager game;

    public PlayerEvents(GameManager game) {
        this.game = game;
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        game.onHunterDeath(event.getPlayer());
    }
}
