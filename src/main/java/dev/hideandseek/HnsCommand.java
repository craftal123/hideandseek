package dev.hideandseek;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

public final class HnsCommand implements CommandExecutor, TabCompleter {
    private final Plugin plugin;
    private final GameManager game;

    public HnsCommand(Plugin plugin, GameManager game) {
        this.plugin = plugin;
        this.game = game;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(help());
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(sender, args);
            case "stop" -> {
                game.stop();
                sender.sendMessage(ChatColor.YELLOW + "Game stopped.");
            }
            case "ask" -> ask(sender, args);
            case "complete" -> {
                if (!(sender instanceof Player p)) {
                    sender.sendMessage("Players only.");
                } else {
                    sender.sendMessage(game.completeChallenge(p));
                }
            }
            case "disguise" -> disguise(sender, args);
            case "status" -> sender.sendMessage(game.status());
            case "found" -> found(sender, args);
            case "open" -> openMenu(sender);
            default -> sender.sendMessage(help());
        }
        return true;
    }

    private void openMenu(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        game.openMainMenu(p);
    }

    private void start(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(ChatColor.RED + "Usage: /hns start <hider> <hunter1,hunter2,...> [hideSeconds]");
            return;
        }

        Player hider = Bukkit.getPlayerExact(args[1]);
        if (hider == null) {
            sender.sendMessage(ChatColor.RED + "Hider not found online.");
            return;
        }

        List<Player> hunters = Arrays.stream(args[2].split(","))
                .map(Bukkit::getPlayerExact)
                .filter(p -> p != null && !p.getUniqueId().equals(hider.getUniqueId()))
                .collect(Collectors.toList());

        if (hunters.isEmpty()) {
            sender.sendMessage(ChatColor.RED + "Need at least one online hunter.");
            return;
        }

        int hideSeconds = args.length >= 4 ? safeParseInt(args[3], 1200) : 1200;
        game.start(hider, hunters, hideSeconds);
        sender.sendMessage(ChatColor.GREEN + "Started game. Hider must press I'm Ready in minimap menu.");
    }

    private void ask(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 2) {
            game.openQuestionsMenu(p);
            sender.sendMessage(ChatColor.YELLOW + "Opened question menu. Or use /hns ask <questionId>");
            return;
        }
        sender.sendMessage(game.askQuestionById(p, args[1]));
    }

    private void disguise(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /hns disguise <block_material>");
            return;
        }

        Material material = Material.matchMaterial(args[1]);
        if (material == null) {
            sender.sendMessage(ChatColor.RED + "Unknown material.");
            return;
        }

        sender.sendMessage(game.disguise(p, material));
    }

    private void found(CommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /hns found <hiderName>");
            return;
        }
        var hider = game.getHider();
        if (hider == null) {
            sender.sendMessage(ChatColor.YELLOW + "No game running.");
            return;
        }
        if (!hider.getName().equalsIgnoreCase(args[1])) {
            sender.sendMessage(ChatColor.RED + "That player is not the current hider.");
            return;
        }

        Bukkit.broadcastMessage(ChatColor.GOLD + hider.getName() + " has been found with " + game.getSecondsRemaining() + "s left!");
        game.stop();
    }

    private int safeParseInt(String raw, int fallback) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            plugin.getLogger().warning("Invalid integer: " + raw + ". Using fallback=" + fallback);
            return fallback;
        }
    }

    private String help() {
        return ChatColor.AQUA + "/hns start <hider> <huntersCsv> [seconds], /hns open, /hns ask [id], /hns complete, /hns disguise <block>, /hns status, /hns found <hider>, /hns stop";
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("start", "stop", "open", "ask", "complete", "disguise", "status", "found");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ask")) {
            return List.of("northSouth", "eastWest", "sameBiome", "aboveY75", "distance250", "waterOrLava");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("start") || args[0].equalsIgnoreCase("found"))) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("disguise")) {
            List<String> blocks = new ArrayList<>();
            for (Material value : Material.values()) {
                if (value.isBlock()) blocks.add(value.name().toLowerCase(Locale.ROOT));
                if (blocks.size() >= 30) break;
            }
            return blocks;
        }
        return List.of();
    }
}
