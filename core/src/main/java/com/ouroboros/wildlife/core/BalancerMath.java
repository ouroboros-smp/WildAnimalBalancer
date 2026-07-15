package com.ouroboros.wildlife.core;

import java.util.ArrayList;
import java.util.List;

/** Pure decisions shared by the Paper and Fabric balancers. */
public final class BalancerMath {
    private BalancerMath() {}

    /** Returns the demand-scaled target for an occupied area. */
    public static int targetFor(Settings settings, int localPlayers) {
        int extra = Math.max(0, localPlayers - 1);
        return Math.min(settings.maxTarget(), settings.baseTarget() + settings.perPlayer() * extra);
    }

    /** Returns the deficit to fill this cycle after applying the cycle cap. */
    public static int spawnCount(Settings settings, int target, int wild) {
        int deficit = target - wild;
        return deficit <= 0 ? 0 : Math.min(deficit, settings.maxPerCycle());
    }

    /** Advances or clears a cell's consecutive deficit streak. */
    public static int nextDeficitStreak(int previous, boolean consecutive, boolean hasDeficit) {
        if (!hasDeficit) return 0;
        return consecutive ? previous + 1 : 1;
    }

    /** Applies the current hourly cell budget to a requested spawn count. */
    public static int budgetedSpawns(Settings settings, int wanted, int spentThisWindow) {
        return Math.max(0, Math.min(wanted, settings.cellHourlyBudget() - spentThisWindow));
    }

    /** Selects a biome pool using override, vanilla filter, then global precedence. */
    public static List<String> poolFor(Settings settings, String biomeKey) {
        List<String> override = settings.biomeAnimals().get(biomeKey);
        if (override != null) return override;
        if (settings.vanillaBiomeDefaults()) {
            List<String> vanilla = settings.vanillaBiomeAnimals().get(biomeKey);
            if (vanilla != null) {
                List<String> pool = new ArrayList<>(settings.animals());
                pool.retainAll(vanilla);
                return List.copyOf(pool);
            }
        }
        return settings.animals();
    }

    /** Returns a stable per-world key for a coarse 128-block cell. */
    public static String cellKey(String worldId, int blockX, int blockZ) {
        return worldId + ":" + (blockX >> 7) + ":" + (blockZ >> 7);
    }

    /** Returns whether a breedable animal passes the ownership heuristic. */
    public static boolean isWild(boolean tamed, boolean leashed, boolean named) {
        return !tamed && !leashed && !named;
    }

    /** Records exactly one monitoring result for a completed dispatch outcome. */
    public static void recordCensusOutcome(BalancerStats stats, CensusOutcome outcome) {
        switch (outcome) {
            case RAN -> stats.censusRan();
            case SKIPPED_DISABLED_WORLD -> stats.skippedDisabledWorld();
            case SKIPPED_REGION_BOUNDARY -> stats.skippedRegionBoundary();
            case SKIPPED_SHARED_CELL -> stats.skippedSharedCell();
            case SKIPPED_GONE -> { }
        }
    }
}
