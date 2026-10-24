package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.StringUtil;

public final class Chunkblockmod extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final List<String> BLOCK_MATERIAL_NAMES = Arrays.stream(Material.values())
            .filter(Material::isBlock)
            .map(material -> "minecraft:" + material.name().toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());

    private ModState state;
    private ChunkReplacementManager chunkReplacementManager;
    private boolean actionBarVisible;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.state = new ModState(this);
        EffectManager effectManager = new EffectManager();
        MobSpawnManager mobSpawnManager = new MobSpawnManager(this);
        this.chunkReplacementManager = new ChunkReplacementManager(this, state, effectManager, mobSpawnManager);

        PluginCommand command = getCommand("rc");
        if (command != null) {
            command.setExecutor(this);
            command.setTabCompleter(this);
        } else {
            getLogger().warning("Command /rc was not registered. Check plugin.yml.");
        }

        getServer().getPluginManager().registerEvents(this, this);
        startTimerTask();
        startChunkProcessingTask();
        if (state.getEnabledWorlds().isEmpty()) getLogger().info("No challenge worlds enabled. Use /rc world add <world> before /rc start.");
    }

    @Override
    public void onDisable() {
        if (state != null) state.setActive(false);
        if (chunkReplacementManager != null) chunkReplacementManager.close();
        clearActionBarIfNeeded();
    }

    private void startTimerTask() {
        getServer().getScheduler().runTaskTimer(this, () -> {
            if (!state.isTimerVisible()) {
                clearActionBarIfNeeded();
                return;
            }

            Component message = null;
            if (!state.isActive()) {
                if (state.getElapsedTimeMillis() > 0) {
                    message = Component.text("Paused: " + state.formatDisplayTime(), NamedTextColor.RED);
                }
            } else {
                message = Component.text(state.formatDisplayTime(), NamedTextColor.GREEN);
            }

            if (message == null) {
                clearActionBarIfNeeded();
                return;
            }

            for (Player player : Bukkit.getOnlinePlayers()) {
                if (state.isWorldEnabled(player.getWorld().getName())) player.sendActionBar(message);
            }
            actionBarVisible = true;
        }, 1L, 10L);
    }

    private void clearActionBarIfNeeded() {
        if (!actionBarVisible) {
            return;
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendActionBar(Component.empty());
        }
        actionBarVisible = false;
    }

    private void startChunkProcessingTask() {
        getServer().getScheduler().runTaskTimer(this, chunkReplacementManager::processQueuedChunks, 1L, 1L);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!state.isActive() || event.getTo() == null) {
            return;
        }

        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && (event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)) {
            return;
        }

        handleCurrentChunk(event.getPlayer(), event.getTo());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (!state.isActive() || event.getTo() == null) return;
        if (event.getFrom().getWorld() == event.getTo().getWorld()
                && (event.getFrom().getBlockX() >> 4) == (event.getTo().getBlockX() >> 4)
                && (event.getFrom().getBlockZ() >> 4) == (event.getTo().getBlockZ() >> 4)) return;
        // Teleport events can precede destination loading. Recheck the actual position next tick
        // instead of synchronously generating the destination from an event listener.
        getServer().getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) handleCurrentChunk(player, player.getLocation());
        });
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        getServer().getScheduler().runTask(this, () -> {
            Player player = event.getPlayer();
            if (player.isOnline()) handleCurrentChunk(player, player.getLocation());
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onChunkUnload(ChunkUnloadEvent event) { chunkReplacementManager.onChunkUnload(event.getChunk()); }

    private void handleCurrentChunk(Player player, org.bukkit.Location location) {
        org.bukkit.World world = location.getWorld();
        if (!state.isActive() || world == null || !state.isWorldEnabled(world.getName())) return;
        int x = location.getBlockX() >> 4, z = location.getBlockZ() >> 4;
        if (world.isChunkLoaded(x, z)) chunkReplacementManager.handleChunkEntry(player, world.getChunkAt(x, z));
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("rc")) {
            return false;
        }

        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }

        if (!"status".equalsIgnoreCase(args[0]) && !sender.hasPermission("paperchunkrandomizer.admin")) {
            sender.sendMessage(Component.text("You do not have permission to control the challenge.", NamedTextColor.RED));
            return true;
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        switch (subcommand) {
            case "status" -> {
                sender.sendMessage(Component.text((state.isActive() ? "Running. " : "Paused. ") + chunkReplacementManager.status(), NamedTextColor.AQUA));
                return true;
            }
            case "world" -> {
                if (args.length == 1 || "list".equalsIgnoreCase(args[1])) {
                    sender.sendMessage(Component.text("Challenge worlds: " + String.join(", ", state.getEnabledWorlds()), NamedTextColor.AQUA));
                } else if (args.length == 3 && "add".equalsIgnoreCase(args[1]) && Bukkit.getWorld(args[2]) != null) {
                    state.addWorld(args[2]);
                    sender.sendMessage(Component.text("Challenge world enabled: " + args[2], NamedTextColor.YELLOW));
                } else if (args.length == 3 && "remove".equalsIgnoreCase(args[1])) {
                    state.removeWorld(args[2]);
                    sender.sendMessage(Component.text("Challenge world disabled: " + args[2], NamedTextColor.YELLOW));
                } else {
                    sender.sendMessage(Component.text("Usage: /rc world <list|add <loaded-world>|remove <world>>", NamedTextColor.RED));
                }
                return true;
            }
            case "start" -> {
                if (state.getEnabledWorlds().isEmpty()) {
                    sender.sendMessage(Component.text("First enable a disposable challenge world with /rc world add <world>. Terrain changes are permanent.", NamedTextColor.RED));
                    return true;
                }
                boolean shouldGiveBoots = !state.isActive() && state.getElapsedTimeMillis() == 0;
                state.setActive(true);
                if (shouldGiveBoots) {
                    Bukkit.getOnlinePlayers().stream().filter(p -> state.isWorldEnabled(p.getWorld().getName())).forEach(chunkReplacementManager::giveChunkWalkerBoots);
                }
                Bukkit.getOnlinePlayers().forEach(p -> handleCurrentChunk(p, p.getLocation()));
                sender.sendMessage(Component.text("Chunk randomizer started.", NamedTextColor.GREEN));
                return true;
            }
            case "stop" -> {
                state.setActive(false);
                sender.sendMessage(Component.text("Chunk randomizer paused.", NamedTextColor.RED));
                return true;
            }
            case "reset" -> {
                state.resetTimer();
                sender.sendMessage(Component.text("Timer reset to 00:00:00.", NamedTextColor.YELLOW));
                return true;
            }
            case "effects" -> {
                state.setEffectsEnabled(!state.areEffectsEnabled());
                sender.sendMessage(Component.text("Effects: " + state.areEffectsEnabled(), NamedTextColor.AQUA));
                return true;
            }
            case "fluids" -> {
                if (args.length < 2) {
                    sender.sendMessage(Component.text("Usage: /rc fluids <true|false>", NamedTextColor.RED));
                    return true;
                }
                if (!"true".equalsIgnoreCase(args[1]) && !"false".equalsIgnoreCase(args[1])) {
                    sender.sendMessage(Component.text("Usage: /rc fluids <true|false>", NamedTextColor.RED));
                    return true;
                }
                state.setReplaceFluids(Boolean.parseBoolean(args[1]));
                sender.sendMessage(Component.text("Replace fluids: " + state.shouldReplaceFluids(), NamedTextColor.AQUA));
                return true;
            }
            case "exclude" -> {
                if (!sender.hasPermission("paperchunkrandomizer.admin")) {
                    sender.sendMessage(Component.text("You do not have permission to manage the blacklist.", NamedTextColor.RED));
                    return true;
                }
                return handleExcludeCommand(sender, args);
            }
            case "debug" -> {
                if (args.length == 1) {
                    state.setDebugLevel(0);
                    sender.sendMessage(Component.text("Debug mode: OFF", NamedTextColor.GRAY));
                    return true;
                }

                Integer level = parseInteger(args[1]);
                if (level == null || level < 0 || level > 3) {
                    sender.sendMessage(Component.text("Usage: /rc debug <0-3>", NamedTextColor.RED));
                    return true;
                }

                state.setDebugLevel(level);
                String message = switch (level) {
                    case 1 -> "ON (Mobs only)";
                    case 2 -> "ON (Chunk Blocktype only)";
                    case 3 -> "ON (Effects/Buffs only)";
                    default -> "OFF";
                };
                sender.sendMessage(Component.text("Debug level set to: " + message, NamedTextColor.GRAY));
                return true;
            }
            case "spawnmonster" -> {
                return handleSpawnMonsterCommand(sender, args);
            }
            case "timer" -> {
                return handleTimerCommand(sender, args);
            }
            default -> {
                sendUsage(sender);
                return true;
            }
        }
    }

    private boolean handleExcludeCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            sender.sendMessage(Component.text("Use /rc exclude add <block> or /rc exclude clear", NamedTextColor.YELLOW));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if ("clear".equals(action)) {
            state.clearCustomBlacklist();
            sender.sendMessage(Component.text("Custom blacklist cleared.", NamedTextColor.YELLOW));
            return true;
        }

        if (!"add".equals(action) || args.length < 3) {
            sender.sendMessage(Component.text("Usage: /rc exclude add <block>", NamedTextColor.RED));
            return true;
        }

        Material block = parseBlockMaterial(args[2]);
        if (block == null) {
            sender.sendMessage(Component.text("Unknown or unsupported block: " + args[2], NamedTextColor.RED));
            return true;
        }

        state.addToBlacklist(block);
        sender.sendMessage(Component.text(block.name() + " will be skipped from now on.", NamedTextColor.GOLD));
        return true;
    }

    private boolean handleSpawnMonsterCommand(CommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /rc spawnmonster <chunks|max> <value>", NamedTextColor.RED));
            return true;
        }

        Integer value = parseInteger(args[2]);
        if (value == null) {
            sender.sendMessage(Component.text("Value must be a number.", NamedTextColor.RED));
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "chunk", "chunks" -> {
                if (value < 1) {
                    sender.sendMessage(Component.text("Chunk interval must be at least 1.", NamedTextColor.RED));
                    return true;
                }
                state.setSpawnInterval(value);
                sender.sendMessage(Component.text("Spawn interval set to every " + value + " visited chunks.", NamedTextColor.GOLD));
                return true;
            }
            case "max" -> {
                if (value < 0 || value > 64) {
                    sender.sendMessage(Component.text("Max mobs must be between 0 and 64.", NamedTextColor.RED));
                    return true;
                }
                state.setMaxMobs(value);
                String message = value == 0
                        ? "Surprise mob spawns disabled."
                        : "Maximum surprise mobs set to " + value + ".";
                sender.sendMessage(Component.text(message, NamedTextColor.GOLD));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /rc spawnmonster <chunks|max> <value>", NamedTextColor.RED));
                return true;
            }
        }
    }

    private boolean handleTimerCommand(CommandSender sender, String[] args) {
        if (args.length == 1) {
            state.setTimerVisible(!state.isTimerVisible());
            sender.sendMessage(Component.text("Timer visible: " + state.isTimerVisible(), NamedTextColor.GREEN));
            return true;
        }

        if (args.length < 3) {
            sender.sendMessage(Component.text("Usage: /rc timer <prefix|suffix> <text>", NamedTextColor.RED));
            return true;
        }

        String value = joinArgs(args, 2);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "prefix" -> {
                state.setTimerPrefix(value);
                sender.sendMessage(Component.text("Timer prefix updated.", NamedTextColor.GREEN));
                return true;
            }
            case "suffix" -> {
                state.setTimerSuffix(value);
                sender.sendMessage(Component.text("Timer suffix updated.", NamedTextColor.GREEN));
                return true;
            }
            default -> {
                sender.sendMessage(Component.text("Usage: /rc timer <prefix|suffix> <text>", NamedTextColor.RED));
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!command.getName().equalsIgnoreCase("rc")) {
            return List.of();
        }

        if (!sender.hasPermission("paperchunkrandomizer.admin")) return args.length == 1 ? copyMatches(args[0], List.of("status")) : List.of();

        if (args.length == 1) {
            return copyMatches(args[0], List.of("start", "stop", "reset", "status", "world", "timer", "effects", "fluids", "exclude", "spawnmonster", "debug"));
        }

        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            return switch (subcommand) {
                case "world" -> copyMatches(args[1], List.of("list", "add", "remove"));
                case "fluids" -> copyMatches(args[1], List.of("true", "false"));
                case "exclude" -> copyMatches(args[1], List.of("add", "clear"));
                case "spawnmonster" -> copyMatches(args[1], List.of("chunk", "chunks", "max"));
                case "timer" -> copyMatches(args[1], List.of("prefix", "suffix"));
                case "debug" -> copyMatches(args[1], List.of("0", "1", "2", "3"));
                default -> List.of();
            };
        }

        if (args.length == 3 && "world".equals(subcommand)) {
            return copyMatches(args[2], "remove".equalsIgnoreCase(args[1]) ? new ArrayList<>(state.getEnabledWorlds()) : Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList());
        }

        if (args.length == 3 && "exclude".equals(subcommand) && "add".equalsIgnoreCase(args[1])) {
            return copyMatches(args[2], BLOCK_MATERIAL_NAMES);
        }

        return List.of();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("/rc status | world <list|add <world>|remove <world>>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc start | stop | reset | effects", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc fluids <true|false>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc timer [prefix|suffix <text>]", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc debug <0-3>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc spawnmonster <chunks|max> <value>", NamedTextColor.YELLOW));
        sender.sendMessage(Component.text("/rc exclude add <block> | clear", NamedTextColor.YELLOW));
    }

    private Material parseBlockMaterial(String input) {
        String normalized = input.toUpperCase(Locale.ROOT)
                .replace("MINECRAFT:", "")
                .replace('-', '_');
        Material material = Material.matchMaterial(normalized);
        return material != null && material.isBlock() ? material : null;
    }

    private Integer parseInteger(String raw) {
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int index = start; index < args.length; index++) {
            if (index > start) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    private List<String> copyMatches(String token, List<String> candidates) {
        List<String> completions = new ArrayList<>();
        StringUtil.copyPartialMatches(token, candidates, completions);
        return completions;
    }
}
