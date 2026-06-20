package com.ouroboros.wildlife;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the shipped config.yml parses into the documented defaults, and that
 * an unknown animal name is reported and skipped rather than crashing the load.
 * YamlConfiguration parsing is standalone, so no MockBukkit server is needed here.
 */
class ConfigParsingTest {

    private FileConfiguration bundledConfig() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml should be on the test classpath (from src/main/resources)");
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
    }

    @Test
    void bundledConfigParsesToDocumentedDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(bundledConfig(), warnings::add);

        assertEquals(30L, s.cycleSeconds());
        assertEquals(96, s.scanRadius());
        assertEquals(8, s.baseTarget());
        assertEquals(4, s.perPlayer());
        assertEquals(40, s.maxTarget());
        assertEquals(6, s.maxPerCycle());
        assertEquals(24, s.minSpawnDist());
        assertEquals(20, s.spawnTries());
        assertEquals(7, s.minSkyLight());
        assertEquals(4, s.animals().size()); // COW, PIG, SHEEP, CHICKEN
        assertTrue(s.enabledWorlds().isEmpty());
        assertTrue(warnings.isEmpty(), "shipped config should produce no unknown-animal warnings");
    }

    @Test
    void unknownAnimalIsReportedAndSkipped() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("animals", List.of("COW", "NOT_A_REAL_MOB"));

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, warnings::add);

        assertEquals(1, s.animals().size());
        assertEquals(List.of("NOT_A_REAL_MOB"), warnings);
    }
}
