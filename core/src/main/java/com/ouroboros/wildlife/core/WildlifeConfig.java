package com.ouroboros.wildlife.core;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/** Shared parsing and validation for both platform configuration files. */
public final class WildlifeConfig {
    public static final String VANILLA_BIOME_RESOURCE = "vanilla-biome-animals.yml";
    private static final List<String> DEFAULT_ANIMALS = List.of(
            "minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken");

    private WildlifeConfig() {}

    /** Parses effective settings using an injected platform species resolver. */
    public static Settings parseSettings(
            YamlSection config,
            Map<String, List<String>> vanillaBiomeAnimals,
            Function<String, Optional<String>> speciesResolver,
            Consumer<String> warn) {
        List<String> animals = resolveSpecies(config.getStringList("animals"), speciesResolver, warn);
        if (animals.isEmpty()) animals = DEFAULT_ANIMALS;

        Map<String, List<String>> biomeAnimals = parseBiomeMap(
                config.getSection("biome-animals"), speciesResolver, warn);
        Set<String> worlds = new LinkedHashSet<>(config.getStringList("enabled-worlds"));

        long cycleSeconds = config.getLong("cycle-seconds", 30);
        if (cycleSeconds < 1) {
            warn.accept("cycle-seconds " + cycleSeconds + " is below 1, clamping to 1");
            cycleSeconds = 1;
        }

        int scanRadius = config.getInt("scan-radius", 96);
        int minSpawnDist = config.getInt("min-spawn-distance", 24);
        if (minSpawnDist > scanRadius) {
            warn.accept("min-spawn-distance " + minSpawnDist + " exceeds scan-radius " + scanRadius
                    + ", clamping to " + scanRadius);
            minSpawnDist = scanRadius;
        }

        int statusLogCycles = config.getInt("status-log-cycles", 0);
        if (statusLogCycles < 0) {
            warn.accept("status-log-cycles " + statusLogCycles
                    + " is negative, disabling the periodic summary");
            statusLogCycles = 0;
        }

        boolean metricsEnabled = config.getBoolean("metrics.enabled", false);
        String metricsBind = config.getString("metrics.bind", "127.0.0.1");
        int metricsPort = config.getInt("metrics.port", 9940);
        if (metricsEnabled && (metricsPort < 1 || metricsPort > 65535)) {
            warn.accept("metrics.port " + metricsPort
                    + " is not a valid port, disabling the metrics endpoint");
            metricsEnabled = false;
        }
        if (metricsEnabled && isWildcardBind(metricsBind)) {
            warn.accept("metrics.bind " + metricsBind + " listens on ALL interfaces; bind the metrics"
                    + " endpoint to localhost or a private scrape network, not the open internet");
        }

        return new Settings(
                cycleSeconds,
                scanRadius,
                config.getInt("base-target", 8),
                config.getInt("per-additional-player", 4),
                config.getInt("max-target", 40),
                config.getInt("max-per-cycle", 6),
                minSpawnDist,
                config.getInt("spawn-tries", 20),
                config.getInt("min-sky-light", 7),
                config.getInt("deficit-cycles", 3),
                config.getInt("cell-hourly-budget", 30),
                config.getBoolean("persistent-spawns", true),
                animals,
                biomeAnimals,
                config.getBoolean("vanilla-biome-defaults", true),
                vanillaBiomeAnimals,
                worlds,
                config.getBoolean("log-spawns", false),
                config.getBoolean("spawn-log-file", false),
                statusLogCycles,
                metricsEnabled,
                metricsBind,
                metricsPort);
    }

    /** Parses biome keys to validated canonical species ids. */
    public static Map<String, List<String>> parseBiomeMap(
            YamlSection section,
            Function<String, Optional<String>> speciesResolver,
            Consumer<String> warn) {
        if (section == null) return Map.of();
        Map<String, List<String>> map = new LinkedHashMap<>();
        for (String biome : section.keys()) {
            map.put(biome.toLowerCase(Locale.ROOT),
                    resolveSpecies(section.getStringList(biome), speciesResolver, warn));
        }
        return Map.copyOf(map);
    }

    /** Parses the bundled vanilla biome snapshot from a resource stream. */
    public static Map<String, List<String>> loadBiomeResource(
            InputStream input,
            Function<String, Optional<String>> speciesResolver,
            Consumer<String> warn) {
        if (input == null) return Map.of();
        return parseBiomeMap(YamlSection.load(input), speciesResolver, warn);
    }

    /** Normalizes legacy enum names and namespaced ids into canonical ids. */
    public static String canonicalSpeciesId(String input) {
        String normalized = input.trim().toLowerCase(Locale.ROOT);
        return normalized.contains(":") ? normalized : "minecraft:" + normalized;
    }

    /** Returns whether a metrics bind exposes every local interface. */
    public static boolean isWildcardBind(String bind) {
        if (bind == null) return true;
        String normalized = bind.trim();
        return normalized.isEmpty() || normalized.equals("0.0.0.0") || normalized.equals("::")
                || normalized.equals("[::]") || normalized.equals("*");
    }

    private static List<String> resolveSpecies(
            List<String> configured,
            Function<String, Optional<String>> speciesResolver,
            Consumer<String> warn) {
        List<String> resolved = new ArrayList<>();
        for (String raw : configured) {
            String canonical = canonicalSpeciesId(raw);
            Optional<String> platformType;
            try {
                platformType = speciesResolver.apply(canonical);
            } catch (RuntimeException exception) {
                platformType = Optional.empty();
            }
            if (platformType.isPresent()) {
                resolved.add(canonicalSpeciesId(platformType.get()));
            } else {
                warn.accept("Unknown animal type in config, skipping: " + raw);
            }
        }
        return List.copyOf(resolved);
    }
}
