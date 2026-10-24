package org.eckelsoft.verification;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Chest;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.eckelsoft.chunkblockmod.Chunkblockmod;
import org.eckelsoft.chunkblockmod.ChunkReplacementManager;
import org.eckelsoft.chunkblockmod.ModState;

/** Integration assertions against actual Paper, using only a disposable flat world. */
public final class RandomizerVerification extends JavaPlugin {
    private Chunkblockmod target;
    private ChunkReplacementManager manager;
    private ModState state;
    private World world;
    private Player player;
    private NamespacedKey completed, progress, material;
    private final List<Chunk> chunks = new ArrayList<>();
    private final List<Double> millis = new ArrayList<>();
    private long started;
    private boolean restart;

    @Override public void onEnable() {
        getServer().getScheduler().runTaskLater(this, () -> {
            try { setup(); } catch (Throwable error) { fail(error); }
        }, 20L);
    }

    private Object field(Object object, String name) throws Exception {
        var field = object.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(object);
    }

    private void setup() throws Exception {
        target = (Chunkblockmod) Bukkit.getPluginManager().getPlugin("Paper-Chunk-Randomizer");
        Bukkit.getScheduler().cancelTasks(target); // Measure only our explicitly driven processing calls.
        manager = (ChunkReplacementManager) field(target, "chunkReplacementManager");
        state = (ModState) field(target, "state");
        world = Bukkit.getWorlds().getFirst();
        completed = new NamespacedKey(target, "completed");
        progress = new NamespacedKey(target, "progress");
        material = new NamespacedKey(target, "target");
        restart = Files.exists(Path.of("probe-phase1.json"));
        PlayerInventory inventory = (PlayerInventory) Proxy.newProxyInstance(getClassLoader(), new Class[]{PlayerInventory.class}, (p, m, a) -> null);
        player = (Player) Proxy.newProxyInstance(getClassLoader(), new Class[]{Player.class}, (p, m, a) -> switch (m.getName()) {
            case "getInventory" -> inventory;
            case "getWorld" -> world;
            case "getName" -> "verification";
            case "getLocation" -> new Location(world, 0, 50, 0);
            case "hasPermission", "isOnline" -> true;
            default -> null;
        });
        if (!restart) {
            command("start");
            check(!state.isActive(), "start must require an explicit challenge world");
            CommandSender guest = (CommandSender) Proxy.newProxyInstance(getClassLoader(), new Class[]{CommandSender.class}, (p, m, a) -> m.getName().equals("hasPermission") ? false : null);
            target.onCommand(guest, target.getCommand("rc"), "rc", new String[]{"world", "add", world.getName()});
            check(!state.isWorldEnabled(world.getName()), "non-admin must not enable challenge worlds");
            command("world", "add", world.getName());
        }
        command("start");
        check(state.isActive(), "challenge must start after world opt-in");
        for (int x = 0; x < 6; x++) {
            final int chunkX = x;
            world.getChunkAtAsync(chunkX, 0, true).thenAccept(chunk -> Bukkit.getScheduler().runTask(this, () -> {
                try {
                    chunk.addPluginChunkTicket(this);
                    chunks.add(chunk);
                    if (chunks.size() == 6) begin();
                } catch (Throwable error) { fail(error); }
            }));
        }
    }

    private Chunk chunk(int x) { return chunks.stream().filter(c -> c.getX() == x).findFirst().orElseThrow(); }
    private void enter(Chunk chunk) { manager.handleChunkEntry(player, chunk); }
    private int queueSize() throws Exception {
        Object queue = field(manager, "pendingChunkWork");
        var size = queue.getClass().getDeclaredMethod("size"); size.setAccessible(true); return (Integer) size.invoke(queue);
    }
    private void command(String... args) { target.onCommand(Bukkit.getConsoleSender(), target.getCommand("rc"), "rc", args); }
    private void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }

    private void begin() throws Exception {
        if (!restart) {
            target.onPlayerTeleport(new PlayerTeleportEvent(player, new Location(world, -32, 50, 0), new Location(world, 0, 50, 0)));
            Bukkit.getScheduler().runTaskLater(this, () -> {
                try {
                    check(queueSize() == 1, "teleport must enqueue the actual destination after it has loaded");
                    beginWork();
                } catch (Throwable error) { fail(error); }
            }, 2L);
        } else {
            beginWork();
        }
    }

    private void beginWork() throws Exception {
        if (restart) {
            for (int i = 0; i < 5; i++) {
                check(chunk(i).getPersistentDataContainer().has(completed, PersistentDataType.BYTE), "completion marker must survive a real restart");
                enter(chunk(i));
            }
            check(queueSize() == 0, "completed chunks must not be requeued after restart");
            check(chunk(5).getPersistentDataContainer().getOrDefault(progress, PersistentDataType.INTEGER, 0) > 0, "partial progress must survive restart");
            enter(chunk(5));
        } else {
            var chest = world.getBlockAt(1, 42, 1); chest.setType(Material.CHEST, false);
            ((Chest) chest.getState()).getBlockInventory().addItem(new ItemStack(Material.DIAMOND, 3));
            world.getBlockAt(2, 42, 1).setType(Material.BEDROCK, false);
            world.getBlockAt(3, 42, 1).setType(Material.END_PORTAL_FRAME, false);
            world.getBlockAt(4, 10, 1).setType(Material.WATER, false);
            world.getBlockAt(5, 42, 1).setType(Material.POPPY, false);
            world.getBlockAt(6, 100, 1).setType(Material.OAK_LOG, false);
            for (int i = 0; i < 5; i++) enter(chunk(i));
            check(queueSize() == 4, "backlog must be capped at four test jobs");
            enter(chunk(0)); check(queueSize() == 4, "duplicate entry must not enqueue duplicate work");
            manager.processQueuedChunks();
            check(chunk(0).getPersistentDataContainer().getOrDefault(progress, PersistentDataType.INTEGER, 0) > 0, "processing must write a checkpoint");
            int checkpoint = chunk(0).getPersistentDataContainer().get(progress, PersistentDataType.INTEGER);
            command("stop");
            for (int i = 0; i < 10; i++) manager.processQueuedChunks();
            check(chunk(0).getPersistentDataContainer().get(progress, PersistentDataType.INTEGER) == checkpoint, "stop must pause block editing");
            check(queueSize() == 4, "pause must retain pending work");
            manager.onChunkUnload(chunk(0)); check(queueSize() == 3, "unloading must release queued work");
            command("start"); enter(chunk(0));
            check(queueSize() == 4, "revisit must resume checkpointed work");
        }
        started = System.nanoTime();
        new BukkitRunnable() {
            int ticks;
            boolean retriedOverflow;
            @Override public void run() {
                try {
                    long start = System.nanoTime(); manager.processQueuedChunks();
                    millis.add((System.nanoTime() - start) / 1_000_000.0);
                    check(++ticks < 2000, "work must finish within 2000 ticks");
                    if (queueSize() == 0) {
                        if (!restart && !retriedOverflow) {
                            check(!chunk(4).getPersistentDataContainer().has(completed, PersistentDataType.BYTE), "overflow chunk must remain retryable");
                            enter(chunk(4)); retriedOverflow = true; return;
                        }
                        finish(); cancel();
                    }
                } catch (Throwable error) { cancel(); fail(error); }
            }
        }.runTaskTimer(this, 1L, 1L);
    }

    private void finish() throws Exception {
        if (!restart) {
            check(world.getBlockAt(1, 42, 1).getType() == Material.CHEST, "container must survive");
            check(((Chest) world.getBlockAt(1, 42, 1).getState()).getBlockInventory().contains(Material.DIAMOND, 3), "container contents must survive");
            check(world.getBlockAt(2, 42, 1).getType() == Material.BEDROCK, "bedrock must survive");
            check(world.getBlockAt(3, 42, 1).getType() == Material.END_PORTAL_FRAME, "portal frames must survive");
            check(world.getBlockAt(4, 10, 1).getType() == Material.WATER, "source fluids must be protected by default");
            check(world.getBlockAt(5, 42, 1).getType() == Material.AIR, "decorative foliage must be removed");
            Material chosen = Material.valueOf(chunk(0).getPersistentDataContainer().get(material, PersistentDataType.STRING));
            check(world.getBlockAt(6, 100, 1).getType() == chosen, "tree above terrain must be transformed");
            check(chosen != Material.WATER && chosen != Material.LAVA && chosen != Material.FIRE, "target must not create fluid cascades");
            for (int i = 0; i < 5; i++) enter(chunk(i));
            check(queueSize() == 0, "completed chunks must not requeue");
            enter(chunk(5)); manager.processQueuedChunks();
            check(chunk(5).getPersistentDataContainer().getOrDefault(progress, PersistentDataType.INTEGER, 0) > 0, "restart fixture must be partially processed");
        } else {
            check(chunk(5).getPersistentDataContainer().has(completed, PersistentDataType.BYTE), "partial chunk must complete after restart");
        }
        command("stop"); world.save();
        double[] sorted = millis.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        String report = String.format(Locale.ROOT,
                "{\"passed\":true,\"phase\":%d,\"paper\":\"%s\",\"ticks\":%d,\"elapsed_seconds\":%.3f,\"processing_mean_ms\":%.3f,\"processing_p95_ms\":%.3f,\"processing_max_ms\":%.3f,\"status\":\"%s\"}%n",
                restart ? 2 : 1, Bukkit.getMinecraftVersion(), sorted.length, (System.nanoTime() - started) / 1e9,
                Arrays.stream(sorted).average().orElse(0), sorted[(int) Math.floor((sorted.length - 1) * 0.95)], sorted[sorted.length - 1], manager.status());
        Files.writeString(Path.of(restart ? "probe-phase2.json" : "probe-phase1.json"), report);
        getLogger().info("VERIFICATION PASSED " + report);
        Bukkit.shutdown();
    }

    private void fail(Throwable error) {
        getLogger().log(java.util.logging.Level.SEVERE, "VERIFICATION FAILED", error);
        try { Files.writeString(Path.of("probe-failure.txt"), error.toString()); } catch (Exception ignored) { }
        Bukkit.shutdown();
    }
}
