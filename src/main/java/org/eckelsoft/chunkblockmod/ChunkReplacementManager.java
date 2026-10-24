package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkReplacementManager {
    private static final Set<String> EXCLUDED_SUFFIXES = Set.of(
            "_STAIRS", "_SLAB", "_WALL", "_FENCE", "_FENCE_GATE", "_DOOR", "_TRAPDOOR",
            "_BED", "_BUTTON", "_PRESSURE_PLATE", "_BANNER", "_SIGN", "_HANGING_SIGN",
            "_HEAD", "_SKULL", "_TORCH", "_RAIL", "_CARPET"
    );
    private static final Set<String> EXCLUDED_TOKENS = Set.of(
            "PORTAL", "SPAWNER", "CHEST", "SHULKER", "BARRIER", "COMMAND_BLOCK",
            "JIGSAW", "CANDLE", "CORAL", "SAPLING", "FLOWER", "BUSH", "GRASS",
            "VINE", "LEAVES", "ROOT", "FUNGUS", "MUSHROOM", "DRIPLEAF", "POTTED",
            "PANE", "LANTERN", "CAULDRON", "TRIPWIRE", "PISTON", "CAMPFIRE",
            "REDSTONE", "REPEATER", "COMPARATOR", "SCAFFOLD", "LADDER", "ANVIL",
            "BELL", "BEACON", "TNT", "BUBBLE", "POWDER_SNOW", "AMETHYST_BUD",
            "AMETHYST_CLUSTER", "BUDDING_AMETHYST", "FROGSPAWN", "EGG", "PICKLE",
            "WEEPING_VINES", "TWISTING_VINES", "CAVE_VINES", "PITCHER", "CHORUS",
            "MOSS_CARPET", "SHELF", "RESIN", "CONCRETE_POWDER", "SCULK",
            "OBSERVER", "HOPPER", "DISPENSER", "DROPPER", "FURNACE", "BREWING",
            "LECTERN", "JUKEBOX", "CRAFTER", "BARREL", "TRIAL", "VAULT",
            "CONDUIT", "RESPAWN_ANCHOR", "BEE", "SPONGE", "CACTUS", "FARMLAND",
            "DIRT_PATH", "SUGAR_CANE", "MAGMA", "SOUL_SAND", "MYCELIUM", "NYLIUM"
    );
    private static final Set<Material> EXPLICIT_REPLACEMENT_BLACKLIST = Set.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.STRUCTURE_VOID,
            Material.END_PORTAL,
            Material.END_GATEWAY,
            Material.END_PORTAL_FRAME,
            Material.NETHER_PORTAL,
            Material.SPAWNER,
            Material.BARRIER,
            Material.LIGHT,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.JIGSAW
    );
    private static final Set<Material> PROTECTED_BLOCKS = Set.of(
            Material.AIR,
            Material.CAVE_AIR,
            Material.VOID_AIR,
            Material.STRUCTURE_VOID,
            Material.WATER,
            Material.LAVA,
            Material.FIRE,
            Material.END_PORTAL_FRAME,
            Material.END_PORTAL,
            Material.NETHER_PORTAL,
            Material.END_GATEWAY,
            Material.SPAWNER
    );

    private final JavaPlugin plugin;
    private final ModState state;
    private final EffectManager effectManager;
    private final MobSpawnManager mobSpawnManager;
    private final NamespacedKey chunkWalkerKey;
    private final NamespacedKey completedKey;
    private final NamespacedKey progressKey;
    private final NamespacedKey targetKey;
    private final TickWorkQueue<ChunkKey> pendingChunkWork;
    private final List<Material> validReplacementBlocks = new ArrayList<>();
    private final Set<Material> foliage = EnumSet.noneOf(Material.class);
    private final Set<Material> protectedMaterials = EnumSet.noneOf(Material.class);
    private long acceptedChunks;
    private long completedChunks;
    private long changedBlocks;
    private long deferredChunks;
    private boolean backingOff;
    private TickWorkQueue.Result lastTick = new TickWorkQueue.Result(0, 0, 0, 0);

    public ChunkReplacementManager(JavaPlugin plugin, ModState state, EffectManager effectManager, MobSpawnManager mobSpawnManager) {
        this.plugin = plugin;
        this.state = state;
        this.effectManager = effectManager;
        this.mobSpawnManager = mobSpawnManager;
        this.chunkWalkerKey = new NamespacedKey(plugin, "chunk_walker");
        this.completedKey = new NamespacedKey(plugin, "completed");
        this.progressKey = new NamespacedKey(plugin, "progress");
        this.targetKey = new NamespacedKey(plugin, "target");
        this.pendingChunkWork = new TickWorkQueue<>(state.getMaxQueuedChunks());
        reloadBlocks();
        for (Material material : Material.values()) {
            if (material.isLegacy() || !material.isBlock()) continue;
            if (shouldClearDecorativeFoliage(material)) foliage.add(material);
            if (isProtectedBlock(material)) protectedMaterials.add(material);
        }
    }

    public void handleChunkEntry(Player player, Chunk chunk) {
        if (!state.isActive() || !state.isWorldEnabled(chunk.getWorld().getName())) return;
        ItemStack boots = player.getInventory().getBoots();
        if (hasChunkWalker(boots)) {
            if (player.getGameMode() != GameMode.CREATIVE) {
                damageChunkWalkerBoots(player, boots, 10);
            }
            if (state.getDebugLevel() > 0) {
                player.sendMessage(Component.text("[Chunk Walker] Protection active!", NamedTextColor.AQUA));
            }
            return;
        }

        if (state.areEffectsEnabled()) effectManager.applyChunkEffect(player, chunk, state.getDebugLevel());
        ChunkKey key = ChunkKey.of(chunk);
        if (pendingChunkWork.contains(key) || chunk.getPersistentDataContainer().has(completedKey, PersistentDataType.BYTE)) return;
        Material target = readTarget(chunk);
        if (target == null) return;
        if (!pendingChunkWork.offer(key, new QueuedChunkWork(key, target, chunk))) {
            deferredChunks++;
            return;
        }
        acceptedChunks++;
        if (acceptedChunks % state.getSpawnInterval() == 0) {
            mobSpawnManager.triggerSurpriseSpawn(chunk.getWorld(), player, state.getMaxMobs(), state.getDebugLevel());
        }
        if (state.getDebugLevel() == 2) {
            player.sendMessage(Component.text("[LOG] Chunk Block: " + prettify(target), NamedTextColor.GRAY));
        }
    }

    public void giveChunkWalkerBoots(Player player) {
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        ItemMeta meta = boots.getItemMeta();
        meta.itemName(Component.text("Chunk Walker", NamedTextColor.AQUA));
        meta.getPersistentDataContainer().set(chunkWalkerKey, PersistentDataType.BYTE, (byte) 1);
        boots.setItemMeta(meta);
        player.getInventory().addItem(boots);
    }

    private void reloadBlocks() {
        validReplacementBlocks.clear();
        for (Material material : Material.values()) {
            if (isValidReplacementBlock(material)) {
                validReplacementBlocks.add(material);
            }
        }
    }

    private boolean isValidReplacementBlock(Material material) {
        if (material.isLegacy() || !material.isBlock() || EXPLICIT_REPLACEMENT_BLACKLIST.contains(material) || isFluidLike(material)
                || material == Material.ICE || material == Material.FROSTED_ICE) {
            return false;
        }

        if (!material.isSolid() && !isFluidLike(material)) {
            return false;
        }

        if (material.hasGravity() && !isFluidLike(material)) {
            return false;
        }

        String name = material.name();
        for (String suffix : EXCLUDED_SUFFIXES) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }

        for (String token : EXCLUDED_TOKENS) {
            if (name.contains(token)) {
                return false;
            }
        }

        return true;
    }

    public void processQueuedChunks() {
        lastTick = new TickWorkQueue.Result(0, 0, 0, 0);
        backingOff = false;
        if (!state.isActive()) return;
        backingOff = state.shouldBackOff(Bukkit.getAverageTickTime());
        if (backingOff) return;
        try {
            lastTick = pendingChunkWork.run(state.getMaxBlockVisitsPerTick(), (long) (state.getMaxMillisPerTick() * 1_000_000));
        } catch (RuntimeException exception) {
            state.setActive(false);
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Randomizer paused after a processing failure; chunk checkpoints are preserved.", exception);
        }
    }

    public void onChunkUnload(Chunk chunk) { pendingChunkWork.remove(ChunkKey.of(chunk)); }
    public void close() { pendingChunkWork.clear(); }

    public String status() {
        return (backingOff ? "Waiting for server tick time to recover. " : "") + String.format(Locale.ROOT, "Queue: %d/%d, completed: %d, changed blocks: %d, deferred entries: %d, last tick: %d visits / %.3f ms (limit %.2f ms)",
                pendingChunkWork.size(), state.getMaxQueuedChunks(), completedChunks, changedBlocks, deferredChunks,
                lastTick.visits(), lastTick.nanos() / 1_000_000.0, state.getMaxMillisPerTick());
    }

    private Material readTarget(Chunk chunk) {
        String saved = chunk.getPersistentDataContainer().get(targetKey, PersistentDataType.STRING);
        Material material = saved == null ? null : Material.matchMaterial(saved);
        if (material != null && validReplacementBlocks.contains(material) && !state.getCustomBlacklist().contains(material)) return material;
        chunk.getPersistentDataContainer().remove(progressKey);
        return pickReplacementBlock(chunk);
    }

    private Material pickReplacementBlock(Chunk chunk) {
        List<Material> eligible = validReplacementBlocks.stream().filter(m -> !state.getCustomBlacklist().contains(m)).toList();
        if (eligible.isEmpty()) {
            return null;
        }

        long seed = chunk.getWorld().getSeed() ^ (long) chunk.getX() * 341_873_128_712L ^ (long) chunk.getZ() * 132_897_987_541L;
        Random random = new Random(seed);
        return eligible.get(random.nextInt(eligible.size()));
    }

    private boolean hasChunkWalker(ItemStack boots) {
        if (boots == null || boots.getType() == Material.AIR || !boots.hasItemMeta()) {
            return false;
        }

        ItemMeta meta = boots.getItemMeta();
        PersistentDataContainer data = meta.getPersistentDataContainer();
        if (data.has(chunkWalkerKey, PersistentDataType.BYTE)) {
            return true;
        }

        return meta.hasItemName()
                && PlainTextComponentSerializer.plainText().serialize(meta.itemName()).toLowerCase(Locale.ROOT).contains("chunk walker");
    }

    private void damageChunkWalkerBoots(Player player, ItemStack boots, int amount) {
        if (!(boots.getItemMeta() instanceof Damageable damageable)) {
            return;
        }

        int newDamage = damageable.getDamage() + amount;
        if (newDamage >= boots.getType().getMaxDurability()) {
            player.getInventory().setBoots(null);
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            return;
        }

        damageable.setDamage(newDamage);
        boots.setItemMeta((ItemMeta) damageable);
    }

    private boolean isProtectedBlock(Material material) {
        if (PROTECTED_BLOCKS.contains(material)) {
            return true;
        }

        String name = material.name();
        return material == Material.BEDROCK || name.contains("CHEST") || name.endsWith("_BED")
                || name.contains("SHULKER_BOX") || name.contains("FURNACE") || name.equals("BARREL")
                || name.equals("HOPPER") || name.equals("DISPENSER") || name.equals("DROPPER")
                || name.equals("BREWING_STAND") || name.equals("CRAFTER") || name.equals("DECORATED_POT")
                || name.equals("CHISELED_BOOKSHELF");
    }

    private boolean isFluidLike(Material material) {
        return material == Material.WATER || material == Material.LAVA || material == Material.FIRE;
    }

    private boolean shouldClearDecorativeFoliage(Material material) {
        if (Tag.FLOWERS.isTagged(material) || Tag.SAPLINGS.isTagged(material)) {
            return true;
        }

        String name = material.name();
        return material == Material.SHORT_GRASS
                || material == Material.TALL_GRASS
                || material == Material.FERN
                || material == Material.LARGE_FERN
                || material == Material.DEAD_BUSH
                || "LEAF_LITTER".equals(name)
                || "PINK_PETALS".equals(name)
                || name.endsWith("_BUSH")
                || name.endsWith("_ROOTS")
                || name.endsWith("_VINE")
                || name.contains("VINES");
    }

    private String prettify(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private record ChunkKey(java.util.UUID worldId, int x, int z) {
        static ChunkKey of(Chunk chunk) { return new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ()); }
    }

    private final class QueuedChunkWork implements TickWorkQueue.Work {
        private final ChunkKey key;
        private final Material targetBlock;
        private final org.bukkit.block.data.BlockData targetData;
        private final int minHeight;
        private final int height;
        private int position;
        private int ceilingColumn = -1;
        private int ceiling;
        private Chunk chunk;

        private QueuedChunkWork(ChunkKey key, Material targetBlock, Chunk chunk) {
            this.key = key;
            this.targetBlock = targetBlock;
            this.targetData = targetBlock.createBlockData();
            this.minHeight = chunk.getWorld().getMinHeight();
            this.height = chunk.getWorld().getMaxHeight() - minHeight;
            this.position = Math.max(0, Math.min(256 * height, chunk.getPersistentDataContainer().getOrDefault(progressKey, PersistentDataType.INTEGER, 0)));
        }

        @Override
        public boolean isAvailable() {
            World world = Bukkit.getWorld(key.worldId());
            if (world == null || !state.isWorldEnabled(world.getName()) || !world.isChunkLoaded(key.x(), key.z())) return false;
            chunk = world.getChunkAt(key.x(), key.z());
            return true;
        }

        @Override
        public boolean step() {
            if (position >= 256 * height) return true;
            int column = position / height;
            int x = column >> 4, z = column & 15;
            int y = minHeight + position % height;
            if (column != ceilingColumn) {
                ceilingColumn = column;
                ceiling = chunk.getWorld().getHighestBlockYAt((key.x() << 4) + x, (key.z() << 4) + z, HeightMap.WORLD_SURFACE);
            }
            // Skip all air above this column with a single visit; keep caves and trees intact in the scan.
            if (y > ceiling) {
                position = (column + 1) * height;
            } else {
                org.bukkit.block.Block block = chunk.getBlock(x, y, z);
                Material current = block.getType();
                if (!current.isAir() && current != targetBlock) {
                    if (foliage.contains(current)) {
                        block.setType(Material.AIR, false);
                        changedBlocks++;
                    } else if (!protectedMaterials.contains(current) || (isFluidLike(current) && state.shouldReplaceFluids())) {
                        block.setBlockData(targetData, false);
                        changedBlocks++;
                    }
                }
                position++;
            }
            return position >= 256 * height;
        }

        @Override
        public void checkpoint() {
            chunk.getPersistentDataContainer().set(targetKey, PersistentDataType.STRING, targetBlock.name());
            chunk.getPersistentDataContainer().set(progressKey, PersistentDataType.INTEGER, position);
        }

        @Override
        public void complete() {
            checkpoint();
            chunk.getPersistentDataContainer().set(completedKey, PersistentDataType.BYTE, (byte) 1);
            chunk.getPersistentDataContainer().remove(progressKey);
            completedChunks++;
        }
    }
}
