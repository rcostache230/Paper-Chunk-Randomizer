package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

public final class MobSpawnManager {
    private static final int SURPRISE_MOB_LIFETIME_TICKS = 20 * 90;
    private static final int MAX_NEARBY_SURPRISE_MOBS = 12;
    private static final Random RANDOM = new Random();
    private final JavaPlugin plugin;
    private final NamespacedKey surpriseMobKey;
    private final List<EntityType> dynamicMobPool = new ArrayList<>();

    public MobSpawnManager(JavaPlugin plugin) {
        this.plugin = plugin;
        this.surpriseMobKey = new NamespacedKey(plugin, "surprise_mob");
        for (EntityType type : EntityType.values()) {
            if (type == null
                    || type == EntityType.ENDER_DRAGON
                    || type == EntityType.WITHER
                    || type == EntityType.WARDEN) {
                continue;
            }
            if (type.isSpawnable() && type.isAlive()) {
                dynamicMobPool.add(type);
            }
        }
        dynamicMobPool.add(EntityType.LIGHTNING_BOLT);
    }

    public void triggerSurpriseSpawn(World world, Player player, int maxMobs, int debugLevel) {
        if (dynamicMobPool.isEmpty() || maxMobs <= 0) {
            return;
        }

        Location base = player.getLocation();
        int nearbyTrackedMobs = countNearbyTrackedMobs(base);
        if (nearbyTrackedMobs >= MAX_NEARBY_SURPRISE_MOBS) {
            if (debugLevel == 1) {
                player.sendMessage(Component.text("Surprise spawn skipped: too many plugin mobs nearby.", NamedTextColor.GRAY));
            }
            return;
        }

        int amount = Math.min(RANDOM.nextInt(maxMobs) + 1, MAX_NEARBY_SURPRISE_MOBS - nearbyTrackedMobs);

        for (int index = 0; index < amount; index++) {
            EntityType type = dynamicMobPool.get(RANDOM.nextInt(dynamicMobPool.size()));
            Location spawnLocation = base.clone().add(RANDOM.nextInt(11) - 5, 1, RANDOM.nextInt(11) - 5);

            try {
                if (type == EntityType.LIGHTNING_BOLT) {
                    world.strikeLightning(spawnLocation);
                } else {
                    Entity entity = world.spawnEntity(spawnLocation, type);
                    if (entity instanceof LivingEntity livingEntity) {
                        markTracked(livingEntity);
                    }
                }
            } catch (Exception ignored) {
                // Some entity types have special spawn rules; skipping those keeps the surprise pool broad without hard-failing.
            }
        }

        if (debugLevel == 1) {
            player.sendMessage(Component.text(amount + " mobs have spawned!", NamedTextColor.GOLD));
        }
    }

    private int countNearbyTrackedMobs(Location location) {
        return location.getWorld().getNearbyEntities(location, 32, 16, 32, this::isTrackedSurpriseMob).size();
    }

    private boolean isTrackedSurpriseMob(Entity entity) {
        if (!(entity instanceof LivingEntity livingEntity)) {
            return false;
        }

        return livingEntity.getPersistentDataContainer().has(surpriseMobKey, PersistentDataType.BYTE);
    }

    private void markTracked(LivingEntity entity) {
        entity.getPersistentDataContainer().set(surpriseMobKey, PersistentDataType.BYTE, (byte) 1);
        entity.setRemoveWhenFarAway(true);
        // Temporary challenge mobs must not outlive cancelled expiry tasks after a restart.
        entity.setPersistent(false);
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (entity.isValid() && !entity.isDead()) {
                entity.remove();
            }
        }, SURPRISE_MOB_LIFETIME_TICKS);
    }
}
