package com.ouroboros.wildlife;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The census outcome contract: every player dispatch increments exactly one
 * counter in the census family (or none for a gone player), so Prometheus
 * census totals never double-count a decision.
 */
class CensusOutcomeTest {

    private static long censusFamilyTotal(BalancerStats.Snapshot s) {
        return s.censusRuns() + s.skippedRegionBoundary() + s.skippedSharedCell()
                + s.skippedDisabledWorld() + s.censusFailures();
    }

    @Test
    void eachOutcomeIncrementsExactlyOneCensusCounter() {
        for (WildAnimalBalancer.CensusOutcome outcome : WildAnimalBalancer.CensusOutcome.values()) {
            BalancerStats stats = new BalancerStats();
            WildAnimalBalancer.recordCensusOutcome(stats, outcome);
            long expected = outcome == WildAnimalBalancer.CensusOutcome.SKIPPED_GONE ? 0 : 1;
            assertEquals(expected, censusFamilyTotal(stats.snapshot()), "outcome " + outcome);
        }
    }

    @Test
    void outcomesMapToTheirOwnCounters() {
        BalancerStats stats = new BalancerStats();
        WildAnimalBalancer.recordCensusOutcome(stats, WildAnimalBalancer.CensusOutcome.RAN);
        WildAnimalBalancer.recordCensusOutcome(stats, WildAnimalBalancer.CensusOutcome.SKIPPED_REGION_BOUNDARY);
        WildAnimalBalancer.recordCensusOutcome(stats, WildAnimalBalancer.CensusOutcome.SKIPPED_SHARED_CELL);
        WildAnimalBalancer.recordCensusOutcome(stats, WildAnimalBalancer.CensusOutcome.SKIPPED_DISABLED_WORLD);

        BalancerStats.Snapshot s = stats.snapshot();
        assertEquals(1, s.censusRuns());
        assertEquals(1, s.skippedRegionBoundary());
        assertEquals(1, s.skippedSharedCell());
        assertEquals(1, s.skippedDisabledWorld());
        assertEquals(0, s.censusFailures());
    }
}
