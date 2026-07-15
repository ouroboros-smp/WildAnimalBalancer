package com.ouroboros.wildlife.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Verifies that each dispatch result records exactly one census outcome. */
class CensusOutcomeTest {
    private static long censusFamilyTotal(BalancerStats.Snapshot snapshot) {
        return snapshot.censusRuns() + snapshot.skippedRegionBoundary()
                + snapshot.skippedSharedCell() + snapshot.skippedDisabledWorld()
                + snapshot.censusFailures();
    }

    @Test
    void eachOutcomeIncrementsExactlyOneCensusCounter() {
        for (CensusOutcome outcome : CensusOutcome.values()) {
            BalancerStats stats = new BalancerStats();
            BalancerMath.recordCensusOutcome(stats, outcome);
            long expected = outcome == CensusOutcome.SKIPPED_GONE ? 0 : 1;
            assertEquals(expected, censusFamilyTotal(stats.snapshot()), "outcome " + outcome);
        }
    }

    @Test
    void outcomesMapToTheirOwnCounters() {
        BalancerStats stats = new BalancerStats();
        BalancerMath.recordCensusOutcome(stats, CensusOutcome.RAN);
        BalancerMath.recordCensusOutcome(stats, CensusOutcome.SKIPPED_REGION_BOUNDARY);
        BalancerMath.recordCensusOutcome(stats, CensusOutcome.SKIPPED_SHARED_CELL);
        BalancerMath.recordCensusOutcome(stats, CensusOutcome.SKIPPED_DISABLED_WORLD);

        BalancerStats.Snapshot snapshot = stats.snapshot();
        assertEquals(1, snapshot.censusRuns());
        assertEquals(1, snapshot.skippedRegionBoundary());
        assertEquals(1, snapshot.skippedSharedCell());
        assertEquals(1, snapshot.skippedDisabledWorld());
        assertEquals(0, snapshot.censusFailures());
    }
}
