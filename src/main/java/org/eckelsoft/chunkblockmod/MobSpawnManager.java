package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

public final class MobSpawnManager {
    private static final Random RANDOM = new Random();
    private final List<EntityType> dynamicMobPool = new ArrayList<>();

    public MobSpawnManager() {
        for (EntityType type : EntityType.values()) {
            if (type == null || type == EntityType.ENDER_DRAGON) {
                continue;
            }
            if (type.isSpawnable() && type.isAlive()) {
                dynamicMobPool.add(type);
            }
        }

        if (!dynamicMobPool.contains(EntityType.WITHER)) {
            dynamicMobPool.add(EntityType.WITHER);
        }
        dynamicMobPool.add(EntityType.LIGHTNING_BOLT);
    }

    public void triggerSurpriseSpawn(World world, Player player, int maxMobs, int debugLevel) {
        if (dynamicMobPool.isEmpty()) {
            return;
        }

        int amount = RANDOM.nextInt(maxMobs) + 1;
        Location base = player.getLocation();

        for (int index = 0; index < amount; index++) {
            EntityType type = dynamicMobPool.get(RANDOM.nextInt(dynamicMobPool.size()));
            Location spawnLocation = base.clone().add(RANDOM.nextInt(11) - 5, 1, RANDOM.nextInt(11) - 5);

            try {
                if (type == EntityType.LIGHTNING_BOLT) {
                    world.strikeLightning(spawnLocation);
                } else {
                    world.spawnEntity(spawnLocation, type);
                }
            } catch (Exception ignored) {
                // Some entity types have special spawn rules; skipping those keeps the surprise pool broad without hard-failing.
            }
        }

        if (debugLevel == 1) {
            player.sendMessage(Component.text(amount + " mobs have spawned!", NamedTextColor.GOLD));
        }
    }
}
