package org.eckelsoft.chunkblockmod;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class ModStateTest {
    private ModState state(YamlConfiguration config) {
        var plugin = mock(JavaPlugin.class);
        when(plugin.getConfig()).thenReturn(config);
        return new ModState(plugin);
    }

    @Test void oldConfigsRequireExplicitWorldOptInAndGetBoundedDefaults() {
        var state = state(new YamlConfiguration());
        assertFalse(state.isActive());
        assertFalse(state.isWorldEnabled("world"));
        assertEquals(64, state.getMaxQueuedChunks());
        assertEquals(1024, state.getMaxBlockVisitsPerTick());
        assertEquals(2.0, state.getMaxMillisPerTick());
    }

    @Test void malformedBudgetsCannotCreateUnboundedOrPermanentlyStalledWork() {
        var config = new YamlConfiguration();
        config.set("performance.max-block-visits-per-tick", -4);
        config.set("performance.max-queued-chunks", Integer.MAX_VALUE);
        config.set("performance.max-millis-per-tick", Double.NaN);
        var state = state(config);
        assertEquals(1, state.getMaxBlockVisitsPerTick());
        assertEquals(1024, state.getMaxQueuedChunks());
        assertEquals(2.0, state.getMaxMillisPerTick());
    }

    @Test void worldNamesAreExplicitAndPersistWhenChanged() {
        var config = new YamlConfiguration();
        config.set("enabled-worlds", List.of("challenge"));
        var state = state(config);
        assertTrue(state.isWorldEnabled("challenge"));
        assertFalse(state.isWorldEnabled("lobby"));
        state.addWorld("arena"); state.removeWorld("challenge");
        assertEquals(List.of("arena"), config.getStringList("enabled-worlds"));
        assertThrows(UnsupportedOperationException.class, () -> state.getEnabledWorlds().add("world"));
    }

    @Test void yieldsToAnOverloadedServerButCanBeDisabledForBenchmarking() {
        var state = state(new YamlConfiguration());
        assertFalse(state.shouldBackOff(39.9));
        assertTrue(state.shouldBackOff(40));
        assertTrue(state.shouldBackOff(100));
        var config = new YamlConfiguration();
        config.set("performance.pause-above-mspt", 0);
        assertFalse(state(config).shouldBackOff(100));
    }
}
