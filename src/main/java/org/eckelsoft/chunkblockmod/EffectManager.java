package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

public final class EffectManager {
    private final List<PotionEffectType> validEffects = new ArrayList<>();

    public EffectManager() {
        for (PotionEffectType effect : PotionEffectType.values()) {
            if (effect == null
                    || effect == PotionEffectType.BLINDNESS
                    || effect == PotionEffectType.DARKNESS
                    || effect == PotionEffectType.NAUSEA) {
                continue;
            }
            validEffects.add(effect);
        }
    }

    public void applyChunkEffect(Player player, Chunk chunk, int debugLevel) {
        if (validEffects.isEmpty()) {
            return;
        }

        long seed = chunk.getWorld().getSeed()
                ^ ((long) chunk.getX() * 49_632L)
                ^ ((long) chunk.getZ() * 32_517L);
        Random random = new Random(seed);
        PotionEffectType effect = validEffects.get(random.nextInt(validEffects.size()));

        if (effect.isInstant()) {
            int amplifier = random.nextInt(2);
            player.addPotionEffect(effect.createEffect(1, amplifier));
            if (debugLevel == 3) {
                player.sendMessage(Component.text("[LOG] Instant effect: " + prettify(effect), NamedTextColor.LIGHT_PURPLE));
            }
            return;
        }

        int amplifier = random.nextInt(5);
        int duration = random.nextBoolean() ? 20 * 8 : 20 * 16;
        player.addPotionEffect(effect.createEffect(duration, amplifier));

        if (debugLevel == 3) {
            player.sendMessage(Component.text(
                    "[LOG] Effect: " + prettify(effect) + " (Duration: " + (duration / 20) + "s, Amp: " + amplifier + ")",
                    NamedTextColor.LIGHT_PURPLE
            ));
        }
    }

    private String prettify(PotionEffectType effect) {
        return effect.getKey().getKey().replace('_', ' ').toLowerCase(Locale.ROOT);
    }
}
