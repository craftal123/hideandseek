package dev.hideandseek;

import org.bukkit.plugin.java.JavaPlugin;

public final class HideAndSeekPlugin extends JavaPlugin {
    private GameManager gameManager;

    @Override
    public void onEnable() {
        this.gameManager = new GameManager(this);

        var command = getCommand("hns");
        if (command != null) {
            var executor = new HnsCommand(this, gameManager);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }

        getServer().getPluginManager().registerEvents(new PlayerEvents(gameManager), this);
        getLogger().info("Hide and Seek loaded.");
    }

    @Override
    public void onDisable() {
        if (gameManager != null) {
            gameManager.stop();
        }
    }
}
