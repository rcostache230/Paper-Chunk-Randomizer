package org.eckelsoft.chunkblockmod;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class ChunkReplacementManager {
    private static final int BLOCK_VISITS_PER_TICK = 2_048;
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
            "CONDUIT", "RESPAWN_ANCHOR", "BEE"
    );
    private static final Set<String> REMOVABLE_ITEM_NAMES = Set.of(
            "STRING", "LEAF_LITTER", "SKELETON_SKULL", "WITHER_SKELETON_SKULL",
            "PINK_PETALS", "ALLIUM", "AZURE_BLUET", "BLUE_ORCHID", "CORNFLOWER",
            "DANDELION", "LILAC", "COCOA_BEANS", "LILY_OF_THE_VALLEY",
            "ORANGE_TULIP", "OXEYE_DAISY", "PEONY", "PINK_TULIP", "POPPY",
            "RED_TULIP", "ROSE_BUSH", "SUNFLOWER", "WHITE_TULIP"
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

    private final ModState state;
    private final EffectManager effectManager;
    private final MobSpawnManager mobSpawnManager;
    private final NamespacedKey chunkWalkerKey;
    private final Set<ChunkKey> visitedChunks = new HashSet<>();
    private final Deque<QueuedChunkWork> pendingChunkWork = new ArrayDeque<>();
    private final List<Material> validReplacementBlocks = new ArrayList<>();

    public ChunkReplacementManager(JavaPlugin plugin, ModState state, EffectManager effectManager, MobSpawnManager mobSpawnManager) {
        this.state = state;
        this.effectManager = effectManager;
        this.mobSpawnManager = mobSpawnManager;
        this.chunkWalkerKey = new NamespacedKey(plugin, "chunk_walker");
        reloadBlocks();
    }

    public void handleChunkEntry(Player player, Chunk chunk) {
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

        ChunkKey key = new ChunkKey(chunk.getWorld().getUID(), chunk.getX(), chunk.getZ());
        boolean firstVisit = visitedChunks.add(key);
        if (!firstVisit) {
            return;
        }

        if (visitedChunks.size() % state.getSpawnInterval() == 0) {
            mobSpawnManager.triggerSurpriseSpawn(chunk.getWorld(), player, state.getMaxMobs(), state.getDebugLevel());
        }

        queueChunkProcessing(chunk, player, key);
    }

    public void giveChunkWalkerBoots(Player player) {
        ItemStack boots = new ItemStack(Material.DIAMOND_BOOTS);
        ItemMeta meta = boots.getItemMeta();
        meta.itemName(Component.text("Chunk Walker", NamedTextColor.AQUA));
        meta.getPersistentDataContainer().set(chunkWalkerKey, PersistentDataType.BYTE, (byte) 1);
        boots.setItemMeta(meta);
        player.getInventory().addItem(boots);
    }

    public void removeExcessItems() {
        for (Player player : playerWorldPlayers()) {
            player.getWorld().getNearbyEntities(player.getLocation(), 36, player.getWorld().getMaxHeight(), 36, entity -> entity instanceof Item)
                    .stream()
                    .map(Item.class::cast)
                    .filter(item -> isRemovableItem(item.getItemStack().getType()))
                    .forEach(Item::remove);
        }
    }

    private List<Player> playerWorldPlayers() {
        List<Player> players = new ArrayList<>();
        for (World world : org.bukkit.Bukkit.getWorlds()) {
            players.addAll(world.getPlayers());
        }
        return players;
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
        if (!material.isBlock() || EXPLICIT_REPLACEMENT_BLACKLIST.contains(material)) {
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
        if (pendingChunkWork.isEmpty()) {
            return;
        }

        int remainingBudget = BLOCK_VISITS_PER_TICK;
        int attempts = pendingChunkWork.size();
        while (remainingBudget > 0 && attempts-- > 0 && !pendingChunkWork.isEmpty()) {
            QueuedChunkWork work = pendingChunkWork.removeFirst();
            World world = Bukkit.getWorld(work.key.worldId());
            if (world == null || !world.isChunkLoaded(work.key.x(), work.key.z())) {
                pendingChunkWork.addLast(work);
                continue;
            }

            Chunk chunk = world.getChunkAt(work.key.x(), work.key.z());
            int processed = processChunkSlice(chunk, work, remainingBudget);
            remainingBudget -= processed;

            if (!work.isComplete()) {
                pendingChunkWork.addLast(work);
            }
        }
    }

    private void queueChunkProcessing(Chunk chunk, Player player, ChunkKey key) {
        if (state.areEffectsEnabled()) {
            effectManager.applyChunkEffect(player, chunk, state.getDebugLevel());
        }

        Material targetBlock = pickReplacementBlock(chunk);
        if (targetBlock == null) {
            return;
        }

        if (state.getDebugLevel() == 2) {
            player.sendMessage(Component.text("[LOG] Chunk Block: " + prettify(targetBlock), NamedTextColor.GRAY));
        }

        pendingChunkWork.addLast(new QueuedChunkWork(key, targetBlock, chunk.getWorld().getMinHeight()));
    }

    private int processChunkSlice(Chunk chunk, QueuedChunkWork work, int budget) {
        World world = chunk.getWorld();
        int maxHeight = world.getMaxHeight();
        int processed = 0;

        while (processed < budget && !work.isComplete()) {
            org.bukkit.block.Block block = chunk.getBlock(work.localX, work.y, work.localZ);
            Material current = block.getType();
            if (!current.isAir()) {
                if (shouldClearDecorativeFoliage(current)) {
                    block.setType(Material.AIR, false);
                } else {
                    boolean isFluid = isFluidLike(current);
                    if ((!isProtectedBlock(current) || (isFluid && state.shouldReplaceFluids()))
                            && current != work.targetBlock) {
                        block.setType(work.targetBlock, false);
                    }
                }
            }

            processed++;
            work.advance(maxHeight, world.getMinHeight());
        }

        return processed;
    }

    private Material pickReplacementBlock(Chunk chunk) {
        if (validReplacementBlocks.isEmpty()) {
            return null;
        }

        long seed = (long) chunk.getX() * 3_121L + (long) chunk.getZ() * 13_289L;
        Random random = new Random(seed);
        Material targetBlock = validReplacementBlocks.get(random.nextInt(validReplacementBlocks.size()));
        int attempts = 0;
        while (state.getCustomBlacklist().contains(targetBlock) && attempts < validReplacementBlocks.size()) {
            random = new Random(seed + (++attempts));
            targetBlock = validReplacementBlocks.get(random.nextInt(validReplacementBlocks.size()));
        }

        return state.getCustomBlacklist().contains(targetBlock) ? null : targetBlock;
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
        return name.contains("CHEST") || name.endsWith("_BED");
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

    private boolean isRemovableItem(Material material) {
        String name = material.name();
        return REMOVABLE_ITEM_NAMES.contains(name) || name.endsWith("_SAPLING") || name.endsWith("_SEEDS");
    }

    private String prettify(Material material) {
        return material.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    private record ChunkKey(UUID worldId, int x, int z) {
    }

    private static final class QueuedChunkWork {
        private final ChunkKey key;
        private final Material targetBlock;
        private int localX;
        private int localZ;
        private int y;

        private QueuedChunkWork(ChunkKey key, Material targetBlock, int minHeight) {
            this.key = key;
            this.targetBlock = targetBlock;
            this.y = minHeight;
        }

        private void advance(int maxHeight, int minHeight) {
            y++;
            if (y < maxHeight) {
                return;
            }

            y = minHeight;
            localZ++;
            if (localZ < 16) {
                return;
            }

            localZ = 0;
            localX++;
        }

        private boolean isComplete() {
            return localX >= 16;
        }
    }
}
