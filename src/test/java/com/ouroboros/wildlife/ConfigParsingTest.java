package com.ouroboros.wildlife;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(bundledConfig(), Map.of(), warnings::add);

        assertEquals(30L, s.cycleSeconds());
        assertEquals(96, s.scanRadius());
        assertEquals(8, s.baseTarget());
        assertEquals(4, s.perPlayer());
        assertEquals(40, s.maxTarget());
        assertEquals(6, s.maxPerCycle());
        assertEquals(24, s.minSpawnDist());
        assertEquals(20, s.spawnTries());
        assertEquals(7, s.minSkyLight());
        assertEquals(3, s.deficitCycles());
        assertEquals(30, s.cellHourlyBudget());
        assertTrue(s.persistentSpawns());
        assertTrue(s.vanillaBiomeDefaults());
        assertEquals(4, s.animals().size()); // COW, PIG, SHEEP, CHICKEN
        assertTrue(s.biomeAnimals().isEmpty());
        assertTrue(s.enabledWorlds().isEmpty());
        // monitoring defaults: counters always on, every output channel off
        assertFalse(s.logSpawns());
        assertFalse(s.spawnLogFile());
        assertEquals(0, s.statusLogCycles());
        assertFalse(s.metricsEnabled());
        assertEquals("127.0.0.1", s.metricsBind());
        assertEquals(9940, s.metricsPort());
        assertTrue(warnings.isEmpty(), "shipped config should produce no unknown-animal warnings");
    }

    @Test
    void invalidMetricsPortDisablesMetricsWithWarning() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("metrics.enabled", true);
        c.set("metrics.port", 70000);

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        assertFalse(s.metricsEnabled(), "an unusable port must disable the endpoint, not crash the enable");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("metrics.port"), warnings.toString());
    }

    @Test
    void negativeStatusLogCyclesIsClampedWithWarning() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("status-log-cycles", -5);

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        assertEquals(0, s.statusLogCycles(), "Settings must report the true effective value");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("status-log-cycles"), warnings.toString());
    }

    @Test
    void unknownAnimalIsReportedAndSkipped() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("animals", List.of("COW", "NOT_A_REAL_MOB"));

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        assertEquals(1, s.animals().size());
        assertEquals(List.of("Unknown animal type in config, skipping: NOT_A_REAL_MOB"), warnings);
    }

    @Test
    void cycleSecondsBelowOneIsClampedWithWarning() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("cycle-seconds", 0);

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        assertEquals(1L, s.cycleSeconds(), "Settings must report the true effective value");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("cycle-seconds"), warnings.toString());
    }

    @Test
    void minSpawnDistanceBeyondScanRadiusIsClampedWithWarning() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("scan-radius", 96);
        c.set("min-spawn-distance", 200);

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        assertEquals(96, s.minSpawnDist(), "spawn ring must stay inside the census box");
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("min-spawn-distance"), warnings.toString());
    }

    @Test
    void biomeOverridesParseWithUnknownSpeciesSkipped() {
        YamlConfiguration c = new YamlConfiguration();
        c.set("biome-animals.SNOWY_PLAINS", List.of("SHEEP", "NOT_A_REAL_MOB"));
        c.set("biome-animals.desert", List.of());

        List<String> warnings = new ArrayList<>();
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(c, Map.of(), warnings::add);

        // keys are normalised to lowercase biome key paths
        assertEquals(List.of(EntityType.SHEEP), s.biomeAnimals().get("snowy_plains"));
        assertTrue(s.biomeAnimals().get("desert").isEmpty(), "empty override disables the biome");
        assertEquals(List.of("Unknown animal type in config, skipping: NOT_A_REAL_MOB"), warnings);
    }

    @Test
    void bundledVanillaBiomeSnapshotParsesCleanly() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<String, List<EntityType>> vanilla;
        try (InputStream in = getClass().getResourceAsStream("/" + WildlifePlugin.VANILLA_BIOME_RESOURCE)) {
            assertNotNull(in, "vanilla-biome-animals.yml should be bundled with the jar");
            vanilla = WildlifePlugin.loadBiomeResource(in, warnings::add);
        }

        assertTrue(warnings.isEmpty(), "bundled snapshot should contain only valid EntityType names: " + warnings);
        // spot checks against known vanilla behaviour
        assertTrue(vanilla.get("plains").contains(EntityType.COW));
        assertFalse(vanilla.get("snowy_plains").contains(EntityType.PIG), "no pigs on snowy plains");
        assertEquals(List.of(EntityType.MOOSHROOM), vanilla.get("mushroom_fields"));
        assertTrue(vanilla.get("pale_garden").isEmpty(), "pale garden is intentionally lifeless");
        // the filter must not wipe out the shipped default species in common biomes
        WildAnimalBalancer.Settings s = WildlifePlugin.parseSettings(bundledConfig(), vanilla, warnings::add);
        assertEquals(s.animals(), WildAnimalBalancer.poolFor(s, "plains"),
                "all four default species should survive the plains filter");
    }
}
