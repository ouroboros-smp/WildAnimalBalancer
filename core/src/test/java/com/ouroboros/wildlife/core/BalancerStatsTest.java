package com.ouroboros.wildlife.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the monitoring counters and their three renderings: the /wildlife
 * status lines, the periodic one-line summary, and the Prometheus text
 * exposition. All pure logic, no server needed.
 */
class BalancerStatsTest {

    private static BalancerStats populated() {
        BalancerStats stats = new BalancerStats();
        stats.censusRan();
        stats.censusRan();
        stats.skippedRegionBoundary();
        stats.skippedSharedCell();
        stats.skippedDisabledWorld();
        stats.censusFailed();
        stats.deficitObserved();
        stats.deficitObserved();
        stats.blockedByStreak();
        stats.blockedByBudget();
        stats.noSpawnSpot();
        stats.emptyBiomePool();
        stats.spawned("world", "cow", 4);
        stats.spawned("world", "sheep", 2);
        stats.spawned("world_the_end", "cow", 1);
        return stats;
    }

    @Test
    void snapshotReflectsEveryCounter() {
        BalancerStats.Snapshot s = populated().snapshot();

        assertEquals(2, s.censusRuns());
        assertEquals(1, s.skippedRegionBoundary());
        assertEquals(1, s.skippedSharedCell());
        assertEquals(1, s.skippedDisabledWorld());
        assertEquals(1, s.censusFailures());
        assertEquals(2, s.deficitsObserved());
        assertEquals(1, s.blockedByStreak());
        assertEquals(1, s.blockedByBudget());
        assertEquals(1, s.noSpawnSpot());
        assertEquals(1, s.emptyBiomePool());
        assertEquals(3, s.spawnGroups());
        assertEquals(7, s.spawnedAnimals());
        assertEquals(Map.of("cow", 5L, "sheep", 2L), s.spawnedBySpecies());
        assertEquals(Map.of("world", 6L, "world_the_end", 1L), s.spawnedByWorld());
    }

    @Test
    void emptySnapshotRendersWithoutSpeciesOrWorldLines() {
        BalancerStats.Snapshot s = new BalancerStats().snapshot();
        List<String> lines = BalancerStats.statusLines(s, new BalancerStats.Gauges(0, 0, 0));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("By species")));
        assertTrue(lines.stream().noneMatch(l -> l.startsWith("By world")));
    }

    @Test
    void statusLinesCarryTheKeyNumbers() {
        List<String> lines = BalancerStats.statusLines(populated().snapshot(),
                new BalancerStats.Gauges(3, 2, 1));
        String joined = String.join("\n", lines);

        assertTrue(joined.contains("2 run"), joined);
        assertTrue(joined.contains("1 skipped at region boundaries"), joined);
        assertTrue(joined.contains("7 animals in 3 herds"), joined);
        assertTrue(joined.contains("cow 5, sheep 2"), joined);
        assertTrue(joined.contains("3 active, 2 on a deficit streak, 1 with hourly budget spent"), joined);
    }

    @Test
    void summaryLineIsOneLine() {
        String line = BalancerStats.summaryLine(populated().snapshot(), new BalancerStats.Gauges(3, 2, 1));
        assertFalse(line.contains("\n"));
        assertTrue(line.contains("spawned 7 in 3 herds"), line);
    }

    @Test
    void prometheusOutputIsWellFormed() {
        String body = BalancerStats.prometheus(populated().snapshot(), new BalancerStats.Gauges(3, 2, 1));

        // every counter sample present with its labels
        assertTrue(body.contains("wildlife_census_total{result=\"run\"} 2"), body);
        assertTrue(body.contains("wildlife_census_total{result=\"skipped_region_boundary\"} 1"), body);
        assertTrue(body.contains("wildlife_census_total{result=\"failed\"} 1"), body);
        assertTrue(body.contains("wildlife_deficit_cycles_total 2"), body);
        assertTrue(body.contains("wildlife_topup_blocked_total{reason=\"deficit_streak\"} 1"), body);
        assertTrue(body.contains("wildlife_topup_blocked_total{reason=\"hourly_budget\"} 1"), body);
        assertTrue(body.contains("wildlife_topup_blocked_total{reason=\"no_spawn_spot\"} 1"), body);
        assertTrue(body.contains("wildlife_topup_blocked_total{reason=\"empty_biome_pool\"} 1"), body);
        assertTrue(body.contains("wildlife_spawn_groups_total 3"), body);
        assertTrue(body.contains("wildlife_spawned_animals_total 7"), body);
        assertTrue(body.contains("wildlife_spawned_species_total{species=\"cow\"} 5"), body);
        assertTrue(body.contains("wildlife_spawned_world_total{world=\"world_the_end\"} 1"), body);
        assertTrue(body.contains("wildlife_cells_active 3"), body);
        assertTrue(body.contains("wildlife_cells_on_deficit_streak 2"), body);
        assertTrue(body.contains("wildlife_cells_budget_spent 1"), body);

        // exposition format basics: HELP and TYPE for each metric, trailing newline
        assertTrue(body.contains("# HELP wildlife_census_total"), body);
        assertTrue(body.contains("# TYPE wildlife_census_total counter"), body);
        assertTrue(body.contains("# TYPE wildlife_cells_active gauge"), body);
        assertTrue(body.endsWith("\n"), "exposition format requires a final newline");
    }

    @Test
    void prometheusLabelValuesAreEscaped() {
        // world names are admin-controlled and can contain anything
        BalancerStats stats = new BalancerStats();
        stats.spawned("we\"ird\\world\n", "cow", 1);
        String body = BalancerStats.prometheus(stats.snapshot(), new BalancerStats.Gauges(0, 0, 0));

        assertTrue(body.contains("wildlife_spawned_world_total{world=\"we\\\"ird\\\\world\\n\"} 1"), body);
        assertEquals("a\\\\b\\\"c\\nd", BalancerStats.promEscape("a\\b\"c\nd"));
    }
}
