package com.ouroboros.wildlife.core;

/** Result of one player dispatch through a platform balancer. */
public enum CensusOutcome {
    SKIPPED_GONE,
    SKIPPED_DISABLED_WORLD,
    SKIPPED_REGION_BOUNDARY,
    SKIPPED_SHARED_CELL,
    RAN
}
