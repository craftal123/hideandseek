package dev.hideandseek;

import org.bukkit.*;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Cow;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import org.bukkit.plugin.Plugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public final class GameManager {
    public static final String MENU_MAIN_TITLE = ChatColor.DARK_GRAY + "Minimap & Ready";
    public static final String MENU_QUESTIONS_TITLE = ChatColor.DARK_GRAY + "Questions";
    public static final String MENU_POWERUPS_TITLE = ChatColor.DARK_GRAY + "Pick Powerups";
    public static final String MENU_MINIMAP_TITLE = ChatColor.DARK_GRAY + "Openable Minimap";
    public static final String MENU_ITEM_NAME = ChatColor.GOLD + "Hide&Seek Menu";

    private final Plugin plugin;
    private UUID hiderId;
    private final Set<UUID> hunterIds = new HashSet<>();
    private final Deque<String> pendingChallenges = new ArrayDeque<>();
    private final Random random = new Random();
    private BukkitTask timerTask;
    private BukkitTask darkMaskTask;
    private long hideEndEpochMillis;
    private long bonusMillis;
    private int pendingPowerupChoices;
    private boolean cancelNextQuestion;
    private boolean hiderLocked;
    private GamePhase phase = GamePhase.IDLE;
    private Location arenaCenter;
    private MapView minimapView;
    private final List<MaskedRegion> maskedRegions = new ArrayList<>();
    private final Map<UUID, Set<DarkBlock>> spoofedDarkBlocksByHunter = new HashMap<>();

    private static final int DARK_RADIUS = 22;

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

    private static final List<QuestionOption> QUESTION_OPTIONS = List.of(
            new QuestionOption("northSouth", "North or South?", QuestionCategory.COMPARING),
            new QuestionOption("eastWest", "East or West?", QuestionCategory.COMPARING),
            new QuestionOption("sameBiome", "Same biome as me?", QuestionCategory.OBSERVATION),
            new QuestionOption("aboveY75", "Above Y=75?", QuestionCategory.DISTANCE),
            new QuestionOption("distance250", "Within 250 blocks of me?", QuestionCategory.DISTANCE),
            new QuestionOption("waterOrLava", "Standing on water/lava?", QuestionCategory.OBSERVATION)
    );

    public GameManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isRunning() {
        return hiderId != null;
    }

    public boolean isHiderLocked() {
        return hiderLocked;
    }

    public boolean isHider(Player p) {
        return hiderId != null && hiderId.equals(p.getUniqueId());
    }

    public void start(Player hider, Collection<Player> hunters, int hideSeconds) {
        stop();
        hiderId = hider.getUniqueId();
        hunterIds.clear();
        hunters.stream().map(Player::getUniqueId).forEach(hunterIds::add);
        pendingChallenges.clear();
        maskedRegions.clear();
        spoofedDarkBlocksByHunter.clear();
        bonusMillis = 0;
        pendingPowerupChoices = 0;
        cancelNextQuestion = false;
        hiderLocked = false;
        phase = GamePhase.HIDING;
        arenaCenter = hider.getLocation().clone();

        hideEndEpochMillis = System.currentTimeMillis() + hideSeconds * 1000L;
        setupMinimapView(hider.getWorld());

        hider.sendMessage(ChatColor.GOLD + "Hide first, then open minimap menu and press 'I'm Ready!'.");
        hider.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 0, false, false));
        giveMenuItem(hider);
        hunters.forEach(this::giveMenuItem);

        broadcast(ChatColor.GREEN + "Hide & Seek started. Waiting for hider ready check-in.");

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

        darkMaskTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tickDarkenedWorld, 20L, 20L);
    }

    public void stop() {
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (darkMaskTask != null) {
            darkMaskTask.cancel();
            darkMaskTask = null;
        }

        restoreAllSpoofedBlocks();

        var hider = getHider();
        if (hider != null) {
            hider.removePotionEffect(PotionEffectType.INVISIBILITY);
            hider.getInventory().setHelmet(null);
        }

        hiderId = null;
        hunterIds.clear();
        pendingChallenges.clear();
        maskedRegions.clear();
        spoofedDarkBlocksByHunter.clear();
        bonusMillis = 0;
        pendingPowerupChoices = 0;
        cancelNextQuestion = false;
        hiderLocked = false;
        phase = GamePhase.IDLE;
        minimapView = null;
    }

    public Player getHider() {
        return hiderId == null ? null : Bukkit.getPlayer(hiderId);
    }

    public boolean isHunter(Player p) {
        return hunterIds.contains(p.getUniqueId());
    }

    public long getSecondsRemaining() {
        return Math.max(0, (hideEndEpochMillis + bonusMillis - System.currentTimeMillis()) / 1000L);
    }

    public void onHunterDeath(Player hunter) {
        if (!isRunning() || !isHunter(hunter)) return;
        bonusMillis += 60_000L;
        broadcast(ChatColor.RED + hunter.getName() + " died. +60s bonus for hider.");
    }

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_MAIN_TITLE);
        inv.setItem(11, named(Material.FILLED_MAP, ChatColor.AQUA + "Minimap", List.of(
                ChatColor.GRAY + "Open the minimap viewer",
                ChatColor.GRAY + "Darkened areas are excluded zones"
        )));
        inv.setItem(13, named(Material.BOOK, ChatColor.YELLOW + "Questions", List.of(
                ChatColor.GRAY + "Open categorized question menu",
                ChatColor.GRAY + "Each category grants powerup picks"
        )));
        inv.setItem(15, named(Material.BLAZE_POWDER, ChatColor.LIGHT_PURPLE + "Powerups", List.of(
                ChatColor.GRAY + "Pending picks: " + pendingPowerupChoices,
                ChatColor.GRAY + "Hider picks manually"
        )));
        if (isHider(player) && phase == GamePhase.HIDING) {
            inv.setItem(22, named(Material.LIME_CONCRETE, ChatColor.GREEN + "I'm Ready!", List.of(
                    ChatColor.GRAY + "Locks hider position",
                    ChatColor.GRAY + "Hunters can start looking"
            )));
        }
        player.openInventory(inv);
    }

    public void openMinimapMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_MINIMAP_TITLE);
        inv.setItem(13, buildMinimapItem());
        inv.setItem(22, named(Material.PAPER, ChatColor.GRAY + "Masked Regions", List.of(
                ChatColor.DARK_GRAY + (maskedRegions.isEmpty() ? "none" : String.join(", ", maskedRegions.stream().map(MaskedRegion::label).toList()))
        )));
        player.openInventory(inv);
    }

    public void openQuestionsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 54, MENU_QUESTIONS_TITLE);
        inv.setItem(0, named(Material.PURPLE_STAINED_GLASS_PANE, ChatColor.LIGHT_PURPLE + "Comparing", List.of(ChatColor.GRAY + "Gives 3 powerup picks")));
        inv.setItem(9, named(Material.BLUE_STAINED_GLASS_PANE, ChatColor.BLUE + "Distance", List.of(ChatColor.GRAY + "Gives 2 powerup picks")));
        inv.setItem(18, named(Material.GREEN_STAINED_GLASS_PANE, ChatColor.GREEN + "Observation", List.of(ChatColor.GRAY + "Gives 2 powerup picks")));
        inv.setItem(27, named(Material.YELLOW_STAINED_GLASS_PANE, ChatColor.YELLOW + "Task", List.of(ChatColor.GRAY + "Gives 1 powerup pick")));

        int slot = 2;
        for (QuestionOption option : QUESTION_OPTIONS) {
            Material icon = switch (option.category) {
                case COMPARING -> Material.SPYGLASS;
                case DISTANCE -> Material.COMPASS;
                case OBSERVATION -> Material.ENDER_EYE;
                case TASK -> Material.GOLDEN_PICKAXE;
            };
            inv.setItem(slot, named(icon, ChatColor.WHITE + option.label, List.of(
                    ChatColor.GRAY + "Category: " + option.category.pretty,
                    ChatColor.GRAY + "Gives hider " + option.category.powerups + " powerups",
                    ChatColor.DARK_GRAY + "ID: " + option.id,
                    ChatColor.YELLOW + "Click to ask"
            )));
            slot += 2;
            if (slot >= 53) break;
        }
        player.openInventory(inv);
    }

    public void openPowerupsMenu(Player player) {
        Inventory inv = Bukkit.createInventory(null, 27, MENU_POWERUPS_TITLE + ChatColor.GRAY + " (" + pendingPowerupChoices + " left)");
        int slot = 10;
        for (PowerupType value : PowerupType.values()) {
            inv.setItem(slot++, named(value.icon, value.display, value.lore));
        }
        player.openInventory(inv);
    }

    public void handleInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        String title = ChatColor.stripColor(event.getView().getTitle());
        if (title == null) return;

        if (title.startsWith("Minimap & Ready") || title.startsWith("Questions") || title.startsWith("Pick Powerups") || title.startsWith("Openable Minimap")) {
            event.setCancelled(true);
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR || !clicked.hasItemMeta()) return;

        String name = ChatColor.stripColor(Objects.requireNonNull(clicked.getItemMeta().getDisplayName()));

        if (title.startsWith("Minimap & Ready")) {
            if ("I'm Ready!".equalsIgnoreCase(name) && isHider(player) && phase == GamePhase.HIDING) {
                phase = GamePhase.SEEKING;
                hiderLocked = true;
                broadcast(ChatColor.GREEN + player.getName() + " is ready. Hunters go!");
                player.closeInventory();
                return;
            }
            if ("Minimap".equalsIgnoreCase(name)) {
                openMinimapMenu(player);
                return;
            }
            if ("Questions".equalsIgnoreCase(name)) {
                openQuestionsMenu(player);
                return;
            }
            if ("Powerups".equalsIgnoreCase(name) && isHider(player)) {
                openPowerupsMenu(player);
            }
            return;
        }

        if (title.startsWith("Openable Minimap") && clicked.getType() == Material.FILLED_MAP) {
            player.getInventory().addItem(buildMinimapItem());
            player.sendMessage(ChatColor.GREEN + "Minimap copied to your inventory.");
            return;
        }

        if (title.startsWith("Questions") && isHunter(player)) {
            QUESTION_OPTIONS.stream()
                    .filter(it -> it.label.equalsIgnoreCase(name))
                    .findFirst()
                    .ifPresent(it -> player.sendMessage(askQuestion(player, it)));
            return;
        }

        if (title.startsWith("Pick Powerups") && isHider(player)) {
            for (PowerupType value : PowerupType.values()) {
                if (ChatColor.stripColor(value.display).equalsIgnoreCase(name)) {
                    player.sendMessage(selectPowerup(player, value));
                    break;
                }
            }
        }
    }

    public String askQuestionById(Player hunter, String questionId) {
        for (QuestionOption option : QUESTION_OPTIONS) {
            if (option.id.equalsIgnoreCase(questionId)) {
                return askQuestion(hunter, option);
            }
        }
        return ChatColor.RED + "Unknown question id.";
    }

    public String askQuestion(Player hunter, QuestionOption option) {
        if (!isHunter(hunter)) return ChatColor.RED + "Only hunters can ask questions.";
        if (phase != GamePhase.SEEKING) return ChatColor.RED + "Wait for hider to press I'm Ready.";
        if (!pendingChallenges.isEmpty()) return ChatColor.RED + "Complete challenge first: " + pendingChallenges.peekFirst();

        String answer = resolveQuestion(option.id, hunter);
        if (cancelNextQuestion) {
            cancelNextQuestion = false;
            answer = "(Canceled by hider powerup)";
        } else {
            applyMaskForQuestion(option.id, answer, hunter);
        }

        String challenge = CHALLENGES.get(random.nextInt(CHALLENGES.size()));
        pendingChallenges.addLast(challenge);
        pendingPowerupChoices += option.category.powerups;

        Player hider = getHider();
        if (hider != null && pendingPowerupChoices > 0) {
            hider.sendMessage(ChatColor.LIGHT_PURPLE + "You earned " + option.category.powerups + " powerup pick(s).");
            openPowerupsMenu(hider);
        }

        broadcast(ChatColor.YELLOW + "Question: " + option.label + " -> " + ChatColor.WHITE + answer);
        broadcast(ChatColor.RED + "Hunters must complete: " + challenge);
        return ChatColor.GREEN + "Question processed.";
    }

    public String completeChallenge(Player hunter) {
        if (!isHunter(hunter)) return ChatColor.RED + "Only hunters can complete challenges.";
        var challenge = pendingChallenges.pollFirst();
        if (challenge == null) return ChatColor.YELLOW + "No pending challenge.";
        broadcast(ChatColor.GREEN + hunter.getName() + " completed challenge: " + challenge);
        return ChatColor.GREEN + "Challenge complete.";
    }

    public String disguise(Player hider, Material block) {
        if (!isHider(hider)) return ChatColor.RED + "Only the hider can disguise.";
        if (!block.isBlock()) return ChatColor.RED + "Must be a block material.";
        hider.getInventory().setHelmet(new ItemStack(block));
        hider.sendMessage(ChatColor.GRAY + "Disguised as: " + block);
        return ChatColor.GREEN + "Disguise updated.";
    }

    public String status() {
        if (!isRunning()) return ChatColor.YELLOW + "No game running.";
        return ChatColor.AQUA + "Phase: " + phase
                + ChatColor.GRAY + " | Hider: " + Objects.requireNonNull(getHider()).getName()
                + ChatColor.GRAY + " | Hunters: " + hunterIds.size()
                + ChatColor.GRAY + " | Remaining: " + getSecondsRemaining() + "s"
                + ChatColor.GRAY + " | Masked regions: " + maskedRegions.size()
                + ChatColor.GRAY + " | Powerups pending: " + pendingPowerupChoices;
    }

    private String selectPowerup(Player hider, PowerupType value) {
        if (pendingPowerupChoices <= 0) return ChatColor.RED + "No powerup picks available.";
        pendingPowerupChoices--;
        switch (value) {
            case TIME_30 -> bonusMillis += 30_000L;
            case TIME_60 -> bonusMillis += 60_000L;
            case CANCEL_NEXT -> cancelNextQuestion = true;
            case DECOY_COW -> spawnDecoyCow();
            case SLOW_HUNTERS -> hunterIds.stream().map(Bukkit::getPlayer).filter(Objects::nonNull)
                    .forEach(p -> p.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 20 * 8, 1, false, true)));
        }
        hider.sendMessage(ChatColor.GREEN + "Applied: " + ChatColor.stripColor(value.display) + ChatColor.GRAY + " (" + pendingPowerupChoices + " left)");
        if (pendingPowerupChoices == 0) hider.closeInventory(); else openPowerupsMenu(hider);
        return ChatColor.GREEN + "Powerup selected.";
    }

    private void spawnDecoyCow() {
        Player hider = getHider();
        if (hider == null) return;
        List<Player> hunters = hunterIds.stream().map(Bukkit::getPlayer).filter(Objects::nonNull).toList();
        if (hunters.isEmpty()) return;
        Player target = hunters.get(random.nextInt(hunters.size()));
        Location spawnLoc = target.getLocation().clone().add(2, 0, 2);
        Cow cow = target.getWorld().spawn(spawnLoc, Cow.class);
        cow.setCustomName(ChatColor.GRAY + "Decoy");
    }

    private void applyMaskForQuestion(String questionId, String answer, Player hunter) {
        Location center = arenaCenter == null ? hunter.getLocation() : arenaCenter;
        switch (questionId.toLowerCase(Locale.ROOT)) {
            case "eastwest", "east_west" -> {
                if (answer.contains("west")) maskedRegions.add(new HalfPlaneMask("East side dark", Axis.X, center.getX(), true));
                else if (answer.contains("east")) maskedRegions.add(new HalfPlaneMask("West side dark", Axis.X, center.getX(), false));
            }
            case "northsouth", "north_south" -> {
                if (answer.contains("north")) maskedRegions.add(new HalfPlaneMask("South side dark", Axis.Z, center.getZ(), true));
                else if (answer.contains("south")) maskedRegions.add(new HalfPlaneMask("North side dark", Axis.Z, center.getZ(), false));
            }
            case "distance250" -> {
                if (answer.equalsIgnoreCase("yes")) maskedRegions.add(new OutsideCircleMask("Outside 250 dark", hunter.getLocation().clone(), 250));
                else if (answer.equalsIgnoreCase("no")) maskedRegions.add(new InsideCircleMask("Inside 250 dark", hunter.getLocation().clone(), 250));
            }
            default -> {
            }
        }
    }

    private String resolveQuestion(String question, Player hunter) {
        Player hider = getHider();
        if (hider == null) return "No hider online.";

        return switch (question.toLowerCase(Locale.ROOT)) {
            case "northsouth", "north_south" -> hider.getLocation().getZ() < hunter.getLocation().getZ() ? "north" : "south";
            case "eastwest", "east_west" -> hider.getLocation().getX() < hunter.getLocation().getX() ? "west" : "east";
            case "samebiome", "same_biome" -> hider.getLocation().getBlock().getBiome() == hunter.getLocation().getBlock().getBiome() ? "yes" : "no";
            case "abovey75", "above_y75" -> hider.getLocation().getY() > 75 ? "yes" : "no";
            case "distance250" -> hider.getLocation().distance(hunter.getLocation()) <= 250 ? "yes" : "no";
            case "waterorlava", "water_or_lava" -> {
                Material near = hider.getLocation().clone().subtract(0, 1, 0).getBlock().getType();
                yield (near == Material.WATER || near == Material.LAVA) ? "yes" : "no";
            }
            default -> "unknown";
        };
    }

    private void setupMinimapView(World world) {
        minimapView = Bukkit.createMap(world);
        minimapView.setScale(MapView.Scale.CLOSE);
        minimapView.setCenterX(arenaCenter.getBlockX());
        minimapView.setCenterZ(arenaCenter.getBlockZ());
        minimapView.setTrackingPosition(true);
        minimapView.setUnlimitedTracking(true);
    }

    private ItemStack buildMinimapItem() {
        ItemStack map = new ItemStack(Material.FILLED_MAP);
        MapMeta meta = (MapMeta) map.getItemMeta();
        if (minimapView != null) {
            meta.setMapView(minimapView);
        }
        meta.setDisplayName(ChatColor.AQUA + "Live Minimap");
        meta.setLore(List.of(
                ChatColor.GRAY + "Open this map item in your hand",
                ChatColor.GRAY + "Dark sides are excluded regions",
                ChatColor.DARK_GRAY + "Masked: " + maskedRegions.size()
        ));
        map.setItemMeta(meta);
        return map;
    }

    private void tickDarkenedWorld() {
        if (maskedRegions.isEmpty()) {
            restoreAllSpoofedBlocks();
            return;
        }

        for (UUID hunterId : hunterIds) {
            Player hunter = Bukkit.getPlayer(hunterId);
            if (hunter == null || !hunter.isOnline()) continue;

            World world = hunter.getWorld();
            Set<DarkBlock> newDark = new HashSet<>();
            for (int dx = -DARK_RADIUS; dx <= DARK_RADIUS; dx++) {
                for (int dz = -DARK_RADIUS; dz <= DARK_RADIUS; dz++) {
                    int bx = hunter.getLocation().getBlockX() + dx;
                    int bz = hunter.getLocation().getBlockZ() + dz;
                    int by = world.getHighestBlockYAt(bx, bz);
                    Location check = new Location(world, bx + 0.5, by, bz + 0.5);
                    if (maskedRegions.stream().anyMatch(mask -> mask.contains(check))) {
                        newDark.add(new DarkBlock(world.getName(), bx, by, bz));
                    }
                }
            }
            applyDarkSpoof(hunter, newDark);
        }
    }

    private void applyDarkSpoof(Player hunter, Set<DarkBlock> newDark) {
        Set<DarkBlock> old = spoofedDarkBlocksByHunter.getOrDefault(hunter.getUniqueId(), Collections.emptySet());
        BlockData darkData = Material.BLACK_STAINED_GLASS.createBlockData();

        for (DarkBlock dark : newDark) {
            if (!old.contains(dark)) {
                hunter.sendBlockChange(dark.toLocation(), darkData);
            }
        }

        for (DarkBlock dark : old) {
            if (!newDark.contains(dark)) {
                hunter.sendBlockChange(dark.toLocation(), dark.toLocation().getBlock().getBlockData());
            }
        }

        spoofedDarkBlocksByHunter.put(hunter.getUniqueId(), newDark);
    }

    private void restoreAllSpoofedBlocks() {
        for (Map.Entry<UUID, Set<DarkBlock>> entry : spoofedDarkBlocksByHunter.entrySet()) {
            Player hunter = Bukkit.getPlayer(entry.getKey());
            if (hunter == null || !hunter.isOnline()) continue;
            for (DarkBlock dark : entry.getValue()) {
                hunter.sendBlockChange(dark.toLocation(), dark.toLocation().getBlock().getBlockData());
            }
        }
        spoofedDarkBlocksByHunter.clear();
    }

    private void giveMenuItem(Player player) {
        player.getInventory().addItem(named(Material.COMPASS, MENU_ITEM_NAME, List.of(
                ChatColor.GRAY + "Right click to open menus",
                ChatColor.GRAY + "Minimap, questions, powerups"
        )));
    }

    private ItemStack named(Material type, String name, List<String> lore) {
        ItemStack stack = new ItemStack(type);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        stack.setItemMeta(meta);
        return stack;
    }

    private void broadcast(String message) {
        Bukkit.getOnlinePlayers().forEach(p -> p.sendMessage(message));
        plugin.getLogger().info(ChatColor.stripColor(message));
    }

    private enum GamePhase {IDLE, HIDING, SEEKING}

    private enum Axis {X, Z}

    private record QuestionOption(String id, String label, QuestionCategory category) {}

    private enum QuestionCategory {
        COMPARING("Comparing", 3),
        DISTANCE("Distance", 2),
        OBSERVATION("Observation", 2),
        TASK("Task", 1);
        private final String pretty;
        private final int powerups;
        QuestionCategory(String pretty, int powerups) {
            this.pretty = pretty;
            this.powerups = powerups;
        }
    }

    private enum PowerupType {
        TIME_30(Material.CLOCK, ChatColor.GREEN + "+30s Time", List.of(ChatColor.GRAY + "Adds 30 seconds")),
        TIME_60(Material.CLOCK, ChatColor.DARK_GREEN + "+60s Time", List.of(ChatColor.GRAY + "Adds 60 seconds")),
        CANCEL_NEXT(Material.BARRIER, ChatColor.RED + "Cancel Next Question", List.of(ChatColor.GRAY + "Cancels next answer")),
        DECOY_COW(Material.COW_SPAWN_EGG, ChatColor.YELLOW + "Spawn Decoy Cow", List.of(ChatColor.GRAY + "Spawns fake clue")),
        SLOW_HUNTERS(Material.SOUL_SAND, ChatColor.AQUA + "Slow Hunters", List.of(ChatColor.GRAY + "Slowness to hunters"));

        private final Material icon;
        private final String display;
        private final List<String> lore;

        PowerupType(Material icon, String display, List<String> lore) {
            this.icon = icon;
            this.display = display;
            this.lore = lore;
        }
    }

    private interface MaskedRegion {
        boolean contains(Location location);
        String label();
    }

    private record HalfPlaneMask(String label, Axis axis, double split, boolean positiveSideMasked) implements MaskedRegion {
        @Override
        public boolean contains(Location location) {
            double value = axis == Axis.X ? location.getX() : location.getZ();
            return positiveSideMasked ? value >= split : value <= split;
        }
    }

    private record OutsideCircleMask(String label, Location center, double radius) implements MaskedRegion {
        @Override
        public boolean contains(Location location) {
            if (!location.getWorld().equals(center.getWorld())) return false;
            return location.distance(center) > radius;
        }
    }

    private record InsideCircleMask(String label, Location center, double radius) implements MaskedRegion {
        @Override
        public boolean contains(Location location) {
            if (!location.getWorld().equals(center.getWorld())) return false;
            return location.distance(center) <= radius;
        }
    }

    private record DarkBlock(String worldName, int x, int y, int z) {
        Location toLocation() {
            World world = Bukkit.getWorld(worldName);
            if (world == null) throw new IllegalStateException("World not found: " + worldName);
            return new Location(world, x, y, z);
        }
    }
}
