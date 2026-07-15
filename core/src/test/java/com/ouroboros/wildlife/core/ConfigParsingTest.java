package com.ouroboros.wildlife.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies shared YAML parsing, defaults, validation, and species normalization. */
class ConfigParsingTest {
    private static final Function<String, Optional<String>> SPECIES_RESOLVER = id ->
            id.equals("minecraft:not_a_real_mob") ? Optional.empty() : Optional.of(id);

    private YamlSection bundledConfig() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input, "config.yml should be on the test classpath");
            return YamlSection.load(input);
        }
    }

    private YamlSection fabricBundledConfig() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/fabric-config.yml")) {
            assertNotNull(input, "fabric config.yml should be on the test classpath");
            return YamlSection.load(input);
        }
    }

    private static Settings parse(YamlSection config, List<String> warnings) {
        return WildlifeConfig.parseSettings(config, Map.of(), SPECIES_RESOLVER, warnings::add);
    }

    @Test
    void bundledConfigParsesToDocumentedDefaults() throws Exception {
        List<String> warnings = new ArrayList<>();
        Settings settings = parse(bundledConfig(), warnings);

        assertEquals(30L, settings.cycleSeconds());
        assertEquals(96, settings.scanRadius());
        assertEquals(8, settings.baseTarget());
        assertEquals(4, settings.perPlayer());
        assertEquals(40, settings.maxTarget());
        assertEquals(6, settings.maxPerCycle());
        assertEquals(24, settings.minSpawnDist());
        assertEquals(20, settings.spawnTries());
        assertEquals(7, settings.minSkyLight());
        assertEquals(3, settings.deficitCycles());
        assertEquals(30, settings.cellHourlyBudget());
        assertTrue(settings.persistentSpawns());
        assertTrue(settings.vanillaBiomeDefaults());
        assertEquals(List.of(
                "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken"),
                settings.animals());
        assertTrue(settings.biomeAnimals().isEmpty());
        assertTrue(settings.enabledWorlds().isEmpty());
        assertFalse(settings.logSpawns());
        assertFalse(settings.spawnLogFile());
        assertEquals(0, settings.statusLogCycles());
        assertFalse(settings.metricsEnabled());
        assertEquals("127.0.0.1", settings.metricsBind());
        assertEquals(9940, settings.metricsPort());
        assertTrue(warnings.isEmpty());
    }

    @Test
    void paperAndFabricBundledConfigsHaveEquivalentDefaults() throws Exception {
        List<String> paperWarnings = new ArrayList<>();
        List<String> fabricWarnings = new ArrayList<>();

        Settings paper = parse(bundledConfig(), paperWarnings);
        Settings fabric = parse(fabricBundledConfig(), fabricWarnings);

        assertEquals(paper, fabric);
        assertTrue(paperWarnings.isEmpty());
        assertTrue(fabricWarnings.isEmpty());
    }

    @Test
    void invalidMetricsPortDisablesMetricsWithWarning() {
        YamlSection config = YamlSection.from(Map.of(
                "metrics", Map.of("enabled", true, "port", 70000)));
        List<String> warnings = new ArrayList<>();

        Settings settings = parse(config, warnings);

        assertFalse(settings.metricsEnabled());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("metrics.port"));
    }

    @Test
    void wildcardMetricsBindWarnsButStaysEnabled() {
        YamlSection config = YamlSection.from(Map.of(
                "metrics", Map.of("enabled", true, "bind", "0.0.0.0")));
        List<String> warnings = new ArrayList<>();

        Settings settings = parse(config, warnings);

        assertTrue(settings.metricsEnabled());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("metrics.bind"));
        assertFalse(WildlifeConfig.isWildcardBind("127.0.0.1"));
        assertTrue(WildlifeConfig.isWildcardBind("::"));
        assertTrue(WildlifeConfig.isWildcardBind(" "));
    }

    @Test
    void clampsCycleDistanceAndStatusValues() {
        YamlSection config = YamlSection.from(Map.of(
                "cycle-seconds", 0,
                "scan-radius", 96,
                "min-spawn-distance", 200,
                "status-log-cycles", -5));
        List<String> warnings = new ArrayList<>();

        Settings settings = parse(config, warnings);

        assertEquals(1L, settings.cycleSeconds());
        assertEquals(96, settings.minSpawnDist());
        assertEquals(0, settings.statusLogCycles());
        assertEquals(3, warnings.size());
    }

    @Test
    void legacyAndNamespacedSpeciesNormalizeWhileUnknownSpeciesAreSkipped() {
        YamlSection config = YamlSection.from(Map.of(
                "animals", List.of("COW", "example:yak", "NOT_A_REAL_MOB")));
        List<String> warnings = new ArrayList<>();

        Settings settings = parse(config, warnings);

        assertEquals(List.of("minecraft:cow", "example:yak"), settings.animals());
        assertEquals(List.of("Unknown animal type in config, skipping: NOT_A_REAL_MOB"), warnings);
    }

    @Test
    void biomeOverridesPreserveEmptyListsAndSkipUnknownSpecies() {
        YamlSection config = YamlSection.from(Map.of(
                "biome-animals", Map.of(
                        "SNOWY_PLAINS", List.of("SHEEP", "NOT_A_REAL_MOB"),
                        "desert", List.of())));
        List<String> warnings = new ArrayList<>();

        Settings settings = parse(config, warnings);

        assertEquals(List.of("minecraft:sheep"), settings.biomeAnimals().get("snowy_plains"));
        assertTrue(settings.biomeAnimals().get("desert").isEmpty());
        assertEquals(List.of("Unknown animal type in config, skipping: NOT_A_REAL_MOB"), warnings);
    }

    @Test
    void bundledVanillaBiomeSnapshotParsesCleanly() throws Exception {
        List<String> warnings = new ArrayList<>();
        Map<String, List<String>> vanilla;
        try (InputStream input = getClass().getResourceAsStream(
                "/" + WildlifeConfig.VANILLA_BIOME_RESOURCE)) {
            assertNotNull(input);
            vanilla = WildlifeConfig.loadBiomeResource(input, SPECIES_RESOLVER, warnings::add);
        }

        assertTrue(warnings.isEmpty());
        assertTrue(vanilla.get("plains").contains("minecraft:cow"));
        assertFalse(vanilla.get("snowy_plains").contains("minecraft:pig"));
        assertEquals(List.of("minecraft:mooshroom"), vanilla.get("mushroom_fields"));
        assertTrue(vanilla.get("pale_garden").isEmpty());

        Settings settings = WildlifeConfig.parseSettings(
                bundledConfig(), vanilla, SPECIES_RESOLVER, warnings::add);
        assertEquals(settings.animals(), BalancerMath.poolFor(settings, "plains"));
    }
}
