package com.ouroboros.wildlife;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Lock-free counters for every decision the balancer makes.
 *
 * Censuses run concurrently on many Folia region threads, so every counter is
 * a LongAdder and the per-species and per-world maps are concurrent; bumping a
 * counter never blocks a region thread. Readers (the /wildlife status command,
 * the periodic summary log, the Prometheus endpoint) take a consistent-enough
 * snapshot without touching any world state.
 *
 * The instance is owned by the plugin and survives /wildlife reload, so the
 * counters cover the server's lifetime and exported Prometheus counters stay
 * monotonic across reloads.
 */
final class BalancerStats {

    // Census outcomes, one per player dispatch.
    private final LongAdder censusRuns = new LongAdder();
    private final LongAdder skippedRegionBoundary = new LongAdder();
    private final LongAdder skippedSharedCell = new LongAdder();
    private final LongAdder skippedDisabledWorld = new LongAdder();
    private final LongAdder censusFailures = new LongAdder();

    // The top-up funnel: a deficit has to survive every guardrail below to
    // become a spawn, and each counter records where a top-up died.
    private final LongAdder deficitsObserved = new LongAdder();
    private final LongAdder blockedByStreak = new LongAdder();
    private final LongAdder blockedByBudget = new LongAdder();
    private final LongAdder noSpawnSpot = new LongAdder();
    private final LongAdder emptyBiomePool = new LongAdder();

    // Successful top-ups.
    private final LongAdder spawnGroups = new LongAdder();
    private final LongAdder spawnedAnimals = new LongAdder();
    private final ConcurrentHashMap<String, LongAdder> spawnedBySpecies = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> spawnedByWorld = new ConcurrentHashMap<>();

    void censusRan() { censusRuns.increment(); }
    void skippedRegionBoundary() { skippedRegionBoundary.increment(); }
    void skippedSharedCell() { skippedSharedCell.increment(); }
    void skippedDisabledWorld() { skippedDisabledWorld.increment(); }
    void censusFailed() { censusFailures.increment(); }
    void deficitObserved() { deficitsObserved.increment(); }
    void blockedByStreak() { blockedByStreak.increment(); }
    void blockedByBudget() { blockedByBudget.increment(); }
    void noSpawnSpot() { noSpawnSpot.increment(); }
    void emptyBiomePool() { emptyBiomePool.increment(); }

    void spawned(String world, String species, int count) {
        spawnGroups.increment();
        spawnedAnimals.add(count);
        spawnedBySpecies.computeIfAbsent(species, k -> new LongAdder()).add(count);
        spawnedByWorld.computeIfAbsent(world, k -> new LongAdder()).add(count);
    }

    /** Point-in-time copy of every counter. Maps are sorted for stable output. */
    record Snapshot(
            long censusRuns,
            long skippedRegionBoundary,
            long skippedSharedCell,
            long skippedDisabledWorld,
            long censusFailures,
            long deficitsObserved,
            long blockedByStreak,
            long blockedByBudget,
            long noSpawnSpot,
            long emptyBiomePool,
            long spawnGroups,
            long spawnedAnimals,
            Map<String, Long> spawnedBySpecies,
            Map<String, Long> spawnedByWorld
    ) {}

    /** Live cell-map sizes, computed by the balancer from its guardrail maps. */
    record Gauges(int activeCells, int cellsOnStreak, int cellsBudgetSpent) {}

    Snapshot snapshot() {
        return new Snapshot(
                censusRuns.sum(),
                skippedRegionBoundary.sum(),
                skippedSharedCell.sum(),
                skippedDisabledWorld.sum(),
                censusFailures.sum(),
                deficitsObserved.sum(),
                blockedByStreak.sum(),
                blockedByBudget.sum(),
                noSpawnSpot.sum(),
                emptyBiomePool.sum(),
                spawnGroups.sum(),
                spawnedAnimals.sum(),
                sumMap(spawnedBySpecies),
                sumMap(spawnedByWorld));
    }

    private static Map<String, Long> sumMap(Map<String, LongAdder> src) {
        Map<String, Long> out = new TreeMap<>();
        src.forEach((k, v) -> out.put(k, v.sum()));
        return out;
    }

    /** Human-readable lines for /wildlife status. Pure, unit tested. */
    static List<String> statusLines(Snapshot s, Gauges g) {
        List<String> lines = new ArrayList<>();
        lines.add("WildAnimalBalancer stats (since server start):");
        lines.add("Censuses: " + s.censusRuns() + " run, "
                + s.skippedRegionBoundary() + " skipped at region boundaries, "
                + s.skippedSharedCell() + " folded into a shared cell, "
                + s.skippedDisabledWorld() + " in disabled worlds, "
                + s.censusFailures() + " failed");
        lines.add("Shortage funnel: " + s.deficitsObserved() + " short cycles, "
                + s.blockedByStreak() + " held by deficit-cycles, "
                + s.blockedByBudget() + " blocked by hourly budget, "
                + s.noSpawnSpot() + " found no spawn spot, "
                + s.emptyBiomePool() + " hit an empty biome pool");
        lines.add("Spawned: " + s.spawnedAnimals() + " animals in " + s.spawnGroups() + " herds");
        if (!s.spawnedBySpecies().isEmpty()) lines.add("By species: " + joinCounts(s.spawnedBySpecies()));
        if (!s.spawnedByWorld().isEmpty()) lines.add("By world: " + joinCounts(s.spawnedByWorld()));
        lines.add("Cells now: " + g.activeCells() + " active, "
                + g.cellsOnStreak() + " on a deficit streak, "
                + g.cellsBudgetSpent() + " with hourly budget spent");
        return lines;
    }

    /** One-line summary for the periodic status log. Pure, unit tested. */
    static String summaryLine(Snapshot s, Gauges g) {
        return "stats: censuses " + s.censusRuns()
                + " (skipped " + s.skippedRegionBoundary() + " boundary, "
                + s.skippedSharedCell() + " shared-cell, "
                + s.skippedDisabledWorld() + " disabled-world, "
                + s.censusFailures() + " failed), spawned "
                + s.spawnedAnimals() + " in " + s.spawnGroups() + " herds, blocked "
                + s.blockedByStreak() + " streak / " + s.blockedByBudget() + " budget / "
                + s.noSpawnSpot() + " no-spot / " + s.emptyBiomePool() + " empty-pool, cells "
                + g.activeCells() + " active / " + g.cellsOnStreak() + " on-streak / "
                + g.cellsBudgetSpent() + " budget-spent";
    }

    /**
     * Prometheus text exposition (format 0.0.4) of the snapshot and gauges.
     * Pure, unit tested. Counter names end in _total per convention.
     */
    static String prometheus(Snapshot s, Gauges g) {
        StringBuilder b = new StringBuilder(2048);

        header(b, "wildlife_census_total", "counter", "Census attempts by result.");
        sample(b, "wildlife_census_total", "result", "run", s.censusRuns());
        sample(b, "wildlife_census_total", "result", "skipped_region_boundary", s.skippedRegionBoundary());
        sample(b, "wildlife_census_total", "result", "skipped_shared_cell", s.skippedSharedCell());
        sample(b, "wildlife_census_total", "result", "skipped_disabled_world", s.skippedDisabledWorld());
        sample(b, "wildlife_census_total", "result", "failed", s.censusFailures());

        header(b, "wildlife_deficit_cycles_total", "counter", "Censused cycles that found the area short.");
        sample(b, "wildlife_deficit_cycles_total", s.deficitsObserved());

        header(b, "wildlife_topup_blocked_total", "counter", "Top-ups withheld, by which guardrail or failure stopped them.");
        sample(b, "wildlife_topup_blocked_total", "reason", "deficit_streak", s.blockedByStreak());
        sample(b, "wildlife_topup_blocked_total", "reason", "hourly_budget", s.blockedByBudget());
        sample(b, "wildlife_topup_blocked_total", "reason", "no_spawn_spot", s.noSpawnSpot());
        sample(b, "wildlife_topup_blocked_total", "reason", "empty_biome_pool", s.emptyBiomePool());

        header(b, "wildlife_spawn_groups_total", "counter", "Herd top-ups performed.");
        sample(b, "wildlife_spawn_groups_total", s.spawnGroups());

        header(b, "wildlife_spawned_animals_total", "counter", "Animals spawned.");
        sample(b, "wildlife_spawned_animals_total", s.spawnedAnimals());

        header(b, "wildlife_spawned_species_total", "counter", "Animals spawned, by species.");
        s.spawnedBySpecies().forEach((k, v) -> sample(b, "wildlife_spawned_species_total", "species", k, v));

        header(b, "wildlife_spawned_world_total", "counter", "Animals spawned, by world.");
        s.spawnedByWorld().forEach((k, v) -> sample(b, "wildlife_spawned_world_total", "world", k, v));

        header(b, "wildlife_cells_active", "gauge", "Cells censused within the last few cycles.");
        sample(b, "wildlife_cells_active", g.activeCells());

        header(b, "wildlife_cells_on_deficit_streak", "gauge", "Cells currently building a shortage streak.");
        sample(b, "wildlife_cells_on_deficit_streak", g.cellsOnStreak());

        header(b, "wildlife_cells_budget_spent", "gauge", "Cells whose hourly spawn budget is fully spent.");
        sample(b, "wildlife_cells_budget_spent", g.cellsBudgetSpent());

        return b.toString();
    }

    private static void header(StringBuilder b, String name, String type, String help) {
        b.append("# HELP ").append(name).append(' ').append(help).append('\n');
        b.append("# TYPE ").append(name).append(' ').append(type).append('\n');
    }

    private static void sample(StringBuilder b, String name, long value) {
        b.append(name).append(' ').append(value).append('\n');
    }

    private static void sample(StringBuilder b, String name, String label, String labelValue, long value) {
        b.append(name).append('{').append(label).append("=\"").append(promEscape(labelValue))
                .append("\"} ").append(value).append('\n');
    }

    /** Escape a Prometheus label value: backslash, double quote, newline. */
    static String promEscape(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String joinCounts(Map<String, Long> counts) {
        StringBuilder b = new StringBuilder();
        counts.forEach((k, v) -> {
            if (b.length() > 0) b.append(", ");
            b.append(k).append(' ').append(v);
        });
        return b.toString();
    }
}
