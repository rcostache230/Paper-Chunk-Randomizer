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
    private final Set<String> enabledWorlds = new HashSet<>();
    private int maxBlockVisitsPerTick;
    private int maxQueuedChunks;
    private double maxMillisPerTick;
    private double pauseAboveMspt;

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

    public int getMaxBlockVisitsPerTick() { return maxBlockVisitsPerTick; }
    public int getMaxQueuedChunks() { return maxQueuedChunks; }
    public double getMaxMillisPerTick() { return maxMillisPerTick; }
    public boolean shouldBackOff(double averageMspt) { return pauseAboveMspt > 0 && averageMspt >= pauseAboveMspt; }
    public boolean isWorldEnabled(String name) { return enabledWorlds.contains(name); }
    public Set<String> getEnabledWorlds() { return Set.copyOf(enabledWorlds); }
    public void addWorld(String name) { enabledWorlds.add(name); save(); }
    public void removeWorld(String name) { enabledWorlds.remove(name); save(); }

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
        maxBlockVisitsPerTick = clamp(config.getInt("performance.max-block-visits-per-tick", 1024), 1, 65_536);
        maxQueuedChunks = clamp(config.getInt("performance.max-queued-chunks", 64), 1, 1024);
        double configuredMillis = config.getDouble("performance.max-millis-per-tick", 2.0);
        maxMillisPerTick = Double.isFinite(configuredMillis) ? Math.max(0.1, Math.min(10.0, configuredMillis)) : 2.0;
        double configuredMspt = config.getDouble("performance.pause-above-mspt", 40.0);
        pauseAboveMspt = Double.isFinite(configuredMspt) ? Math.max(0, Math.min(1000.0, configuredMspt)) : 40.0;
        enabledWorlds.addAll(config.getStringList("enabled-worlds"));
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
        config.set("enabled-worlds", enabledWorlds.stream().sorted().toList());
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
