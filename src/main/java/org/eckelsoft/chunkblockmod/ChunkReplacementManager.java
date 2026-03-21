package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
            "MOSS_CARPET", "SHELF", "RESIN", "CONCRETE_POWDER"
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

        processChunk(chunk, player);
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

    private void processChunk(Chunk chunk, Player player) {
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

        World world = chunk.getWorld();
        for (int localX = 0; localX < 16; localX++) {
            for (int localZ = 0; localZ < 16; localZ++) {
                for (int y = world.getMinHeight(); y < world.getMaxHeight(); y++) {
                    org.bukkit.block.Block block = chunk.getBlock(localX, y, localZ);
                    Material current = block.getType();
                    if (current.isAir()) {
                        continue;
                    }

                    if (shouldClearDecorativeFoliage(current)) {
                        block.setType(Material.AIR, false);
                        continue;
                    }

                    boolean isFluid = isFluidLike(current);
                    if (isProtectedBlock(current) && !(isFluid && state.shouldReplaceFluids())) {
                        continue;
                    }

                    if (current == targetBlock) {
                        continue;
                    }

                    block.setType(targetBlock, false);
                }
            }
        }
    }

    private Material pickReplacementBlock(Chunk chunk) {
        List<Material> allowedBlocks = validReplacementBlocks.stream()
                .filter(material -> !state.getCustomBlacklist().contains(material))
                .toList();

        if (allowedBlocks.isEmpty()) {
            return null;
        }

        long seed = (long) chunk.getX() * 3_121L + (long) chunk.getZ() * 13_289L;
        Random random = new Random(seed);
        return allowedBlocks.get(random.nextInt(allowedBlocks.size()));
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
}
