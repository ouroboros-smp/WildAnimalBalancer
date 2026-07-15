package com.ouroboros.wildlife.core;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Platform-neutral balancer tuning loaded from config.yml. */
public record Settings(
        long cycleSeconds,
        int scanRadius,
        int baseTarget,
        int perPlayer,
        int maxTarget,
        int maxPerCycle,
        int minSpawnDist,
        int spawnTries,
        int minSkyLight,
        int deficitCycles,
        int cellHourlyBudget,
        boolean persistentSpawns,
        List<String> animals,
        Map<String, List<String>> biomeAnimals,
        boolean vanillaBiomeDefaults,
        Map<String, List<String>> vanillaBiomeAnimals,
        Set<String> enabledWorlds,
        boolean logSpawns,
        boolean spawnLogFile,
        int statusLogCycles,
        boolean metricsEnabled,
        String metricsBind,
        int metricsPort
) {
    public Settings {
        animals = List.copyOf(animals);
        biomeAnimals = copyPools(biomeAnimals);
        vanillaBiomeAnimals = copyPools(vanillaBiomeAnimals);
        enabledWorlds = Set.copyOf(enabledWorlds);
    }

    private static Map<String, List<String>> copyPools(Map<String, List<String>> source) {
        Map<String, List<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }
}
