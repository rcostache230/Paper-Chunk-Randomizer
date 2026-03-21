package org.eckelsoft.chunkblockmod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

public final class ModState {
    private final JavaPlugin plugin;
    private final Set<Material> customBlacklist = new HashSet<>();

    private boolean active;
    private int debugLevel;
    private boolean timerVisible;
    private boolean effectsEnabled;
    private int spawnInterval;
    private int maxMobs;
    private boolean replaceFluids;
    private long startTimeMillis;
    private long elapsedBeforeStopMillis;
    private String timerPrefix;
    private String timerSuffix;

    public ModState(JavaPlugin plugin) {
        this.plugin = plugin;
        load();
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        if (active == this.active) {
            return;
        }

        if (active) {
            startTimeMillis = System.currentTimeMillis();
        } else {
            elapsedBeforeStopMillis += System.currentTimeMillis() - startTimeMillis;
        }

        this.active = active;
    }

    public int getDebugLevel() {
        return debugLevel;
    }

    public void setDebugLevel(int debugLevel) {
        this.debugLevel = debugLevel;
        save();
    }

    public boolean isTimerVisible() {
        return timerVisible;
    }

    public void setTimerVisible(boolean timerVisible) {
        this.timerVisible = timerVisible;
        save();
    }

    public boolean areEffectsEnabled() {
        return effectsEnabled;
    }

    public void setEffectsEnabled(boolean effectsEnabled) {
        this.effectsEnabled = effectsEnabled;
        save();
    }

    public int getSpawnInterval() {
        return spawnInterval;
    }

    public void setSpawnInterval(int spawnInterval) {
        this.spawnInterval = spawnInterval;
        save();
    }

    public int getMaxMobs() {
        return maxMobs;
    }

    public void setMaxMobs(int maxMobs) {
        this.maxMobs = clamp(maxMobs, 0, 64);
        save();
    }

    public boolean shouldReplaceFluids() {
        return replaceFluids;
    }

    public void setReplaceFluids(boolean replaceFluids) {
        this.replaceFluids = replaceFluids;
        save();
    }

    public void resetTimer() {
        elapsedBeforeStopMillis = 0L;
        startTimeMillis = active ? System.currentTimeMillis() : 0L;
    }

    public long getElapsedTimeMillis() {
        if (!active) {
            return elapsedBeforeStopMillis;
        }
        return elapsedBeforeStopMillis + (System.currentTimeMillis() - startTimeMillis);
    }

    public String getTimerPrefix() {
        return timerPrefix;
    }

    public void setTimerPrefix(String timerPrefix) {
        this.timerPrefix = timerPrefix;
        save();
    }

    public String getTimerSuffix() {
        return timerSuffix;
    }

    public void setTimerSuffix(String timerSuffix) {
        this.timerSuffix = timerSuffix;
        save();
    }

    public Set<Material> getCustomBlacklist() {
        return customBlacklist;
    }

    public void addToBlacklist(Material block) {
        customBlacklist.add(block);
        save();
    }

    public void clearCustomBlacklist() {
        customBlacklist.clear();
        save();
    }

    public String formatDisplayTime() {
        long timeMs = getElapsedTimeMillis();
        long seconds = (timeMs / 1_000L) % 60;
        long minutes = (timeMs / 60_000L) % 60;
        long hours = timeMs / 3_600_000L;
        String base = String.format("%02d:%02d:%02d", hours, minutes, seconds);
        String prefix = timerPrefix == null || timerPrefix.isBlank() ? "" : timerPrefix + " ";
        String suffix = timerSuffix == null || timerSuffix.isBlank() ? "" : " " + timerSuffix;
        return prefix + base + suffix;
    }

    private void load() {
        FileConfiguration config = plugin.getConfig();
        debugLevel = config.getInt("debug-level", 1);
        timerVisible = config.getBoolean("timer-visible", true);
        effectsEnabled = config.getBoolean("effects-enabled", true);
        spawnInterval = Math.max(1, config.getInt("spawn-interval", 16));
        maxMobs = clamp(config.getInt("max-mobs", 8), 0, 64);
        replaceFluids = config.getBoolean("replace-fluids", false);
        timerPrefix = config.getString("timer-prefix", "");
        timerSuffix = config.getString("timer-suffix", "");

        customBlacklist.clear();
        for (String materialName : config.getStringList("custom-blacklist")) {
            Material material = Material.matchMaterial(
                    materialName.toUpperCase(Locale.ROOT).replace("MINECRAFT:", "").replace('-', '_')
            );
            if (material != null && material.isBlock()) {
                customBlacklist.add(material);
            }
        }
    }

    private void save() {
        FileConfiguration config = plugin.getConfig();
        config.set("debug-level", debugLevel);
        config.set("timer-visible", timerVisible);
        config.set("effects-enabled", effectsEnabled);
        config.set("spawn-interval", spawnInterval);
        config.set("max-mobs", maxMobs);
        config.set("replace-fluids", replaceFluids);
        config.set("timer-prefix", timerPrefix);
        config.set("timer-suffix", timerSuffix);

        List<String> materials = new ArrayList<>(customBlacklist.size());
        for (Material material : customBlacklist) {
            materials.add(material.name().toLowerCase(Locale.ROOT));
        }
        materials.sort(String::compareTo);
        config.set("custom-blacklist", materials);
        plugin.saveConfig();
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
