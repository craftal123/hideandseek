package dev.hideandseek;

import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class GameManager {
    private final Plugin plugin;
    private UUID hiderId;
    private final Set<UUID> hunterIds = new HashSet<>();
    private final Deque<String> pendingChallenges = new ArrayDeque<>();
    private final Random random = new Random();
    private BukkitTask timerTask;
    private long hideEndEpochMillis;
    private long bonusMillis;

    private static final List<String> SABOTAGES = List.of(
            "+180s time bonus",
            "+120s time bonus",
            "Question cancel (next question has no answer)",
            "Teleport hunters to random hider-side location",
            "Spawn a decoy cow",
            "Force hunters to stop moving for 5s"
    );

    private static final List<String> CHALLENGES = List.of(
            "Mine one diamond ore (can be with stone).",
            "Survive a 100-block fall.",
            "Hatch a baby chicken from an egg.",
            "Tame a horse.",
            "Ignite TNT.",
            "Break an iron tool.",
            "Catch a fish.",
            "Breed any two animals.",
            "Touch bedrock."
    );

    public GameManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return hiderId != null;
    }

    public void start(Player hider, Collection<Player> hunters, int hideSeconds) {
        stop();
        hiderId = hider.getUniqueId();
        hunterIds.clear();
        hunters.stream().map(Player::getUniqueId).forEach(hunterIds::add);
        pendingChallenges.clear();
        bonusMillis = 0;

        hideEndEpochMillis = System.currentTimeMillis() + hideSeconds * 1000L;
        hider.sendMessage(ChatColor.GOLD + "You are the hider. Use /hns disguise <block> to blend in.");
        hider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));

        broadcast(ChatColor.GREEN + "Hide & Seek started. Hider: " + hider.getName() + ". Hunters ask with /hns ask <question>."
                + " If hunters die, +60s is added.");

        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            long left = getSecondsRemaining();
            if (left <= 0) {
                broadcast(ChatColor.GOLD + "Timer expired. Hider wins!");
                stop();
                return;
            }
            if (left % 60 == 0 || left <= 30) {
                broadcast(ChatColor.AQUA + "Time left: " + left + "s");
            }
        }, 20L, 20L);
    }

    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }

        var hider = getHider();
        if (hider != null) {
            hider.removePotionEffect(PotionEffectType.INVISIBILITY);
            hider.getInventory().setHelmet(null);
        }

        hiderId = null;
        hunterIds.clear();
        pendingChallenges.clear();
        bonusMillis = 0;
    }

    public Player getHider() {
        return hiderId == null ? null : Bukkit.getPlayer(hiderId);
    }

    public boolean isHunter(Player p) {
        return hunterIds.contains(p.getUniqueId());
    }

    public boolean isHider(Player p) {
        return hiderId != null && hiderId.equals(p.getUniqueId());
    }

    public long getSecondsRemaining() {
        return Math.max(0, (hideEndEpochMillis + bonusMillis - System.currentTimeMillis()) / 1000L);
    }

    public void onHunterDeath(Player hunter) {
        if (!isRunning() || !isHunter(hunter)) {
            return;
        }
        bonusMillis += 60_000L;
        broadcast(ChatColor.RED + hunter.getName() + " died. +60s bonus for hider.");
    }

    public String askQuestion(Player hunter, String questionId) {
        if (!isHunter(hunter)) {
            return ChatColor.RED + "Only hunters can ask questions.";
        }
        if (!pendingChallenges.isEmpty()) {
            return ChatColor.RED + "Complete challenge first: " + pendingChallenges.peekFirst();
        }

        String answer = resolveQuestion(questionId, hunter);
        String sabotage = SABOTAGES.get(random.nextInt(SABOTAGES.size()));
        String challenge = CHALLENGES.get(random.nextInt(CHALLENGES.size()));
        pendingChallenges.addLast(challenge);

        if (sabotage.startsWith("+")) {
            long seconds = Long.parseLong(sabotage.substring(1, sabotage.indexOf('s')));
            bonusMillis += seconds * 1000L;
        }

        broadcast(ChatColor.YELLOW + "Question: " + questionId + " -> " + ChatColor.WHITE + answer);
        broadcast(ChatColor.LIGHT_PURPLE + "Hider sabotage earned: " + sabotage);
        broadcast(ChatColor.RED + "Hunters must complete: " + challenge);
        return ChatColor.GREEN + "Question processed.";
    }

    public String completeChallenge(Player hunter) {
        if (!isHunter(hunter)) {
            return ChatColor.RED + "Only hunters can complete challenges.";
        }
        var challenge = pendingChallenges.pollFirst();
        if (challenge == null) {
            return ChatColor.YELLOW + "No pending challenge.";
        }
        broadcast(ChatColor.GREEN + hunter.getName() + " completed challenge: " + challenge);
        return ChatColor.GREEN + "Challenge complete.";
    }

    public String disguise(Player hider, Material block) {
        if (!isHider(hider)) {
            return ChatColor.RED + "Only the hider can disguise.";
        }
        if (!block.isBlock()) {
            return ChatColor.RED + "Must be a block material.";
        }
        hider.getInventory().setHelmet(new ItemStack(block));
        hider.sendMessage(ChatColor.GRAY + "Disguised as: " + block);
        return ChatColor.GREEN + "Disguise updated.";
    }

    public String status() {
        if (!isRunning()) {
            return ChatColor.YELLOW + "No game running.";
        }
        return ChatColor.AQUA + "Hider: " + Objects.requireNonNull(getHider()).getName()
                + ChatColor.GRAY + " | Hunters: " + hunterIds.size()
                + ChatColor.GRAY + " | Remaining: " + getSecondsRemaining() + "s"
                + ChatColor.GRAY + " | Pending challenge: " + (pendingChallenges.peekFirst() == null ? "none" : pendingChallenges.peekFirst());
    }

    private String resolveQuestion(String question, Player hunter) {
        Player hider = getHider();
        if (hider == null) {
            return "No hider online.";
        }

        return switch (question.toLowerCase(Locale.ROOT)) {
            case "northsouth", "north_south" -> hider.getLocation().getZ() < hunter.getLocation().getZ() ? "north of you" : "south of you";
            case "eastwest", "east_west" -> hider.getLocation().getX() < hunter.getLocation().getX() ? "west of you" : "east of you";
            case "samebiome", "same_biome" -> hider.getWorld().equals(hunter.getWorld())
                    && hider.getLocation().getBlock().getBiome() == hunter.getLocation().getBlock().getBiome()
                    ? "yes" : "no";
            case "abovey75", "above_y75" -> hider.getLocation().getY() > 75 ? "yes" : "no";
            case "distance250" -> hider.getLocation().distance(hunter.getLocation()) <= 250 ? "yes" : "no";
            case "waterorlava", "water_or_lava" -> {
                Material near = hider.getLocation().clone().subtract(0, 1, 0).getBlock().getType();
                yield (near == Material.WATER || near == Material.LAVA) ? "yes" : "no";
            }
            default -> "unknown question id. Try: northSouth, eastWest, sameBiome, aboveY75, distance250, waterOrLava";
        };
    }

    private void broadcast(String message) {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
        plugin.getLogger().info(ChatColor.stripColor(message));
    }
}
