package com.ouroboros.wildlife;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the spawn target math. No server required.
 * Mirrors the defaults documented in the README and config.yml.
 */
class TargetMathTest {

    private static WildAnimalBalancer.Settings cfg(int base, int perPlayer, int max, int maxPerCycle) {
        return new WildAnimalBalancer.Settings(
                30L, 96, base, perPlayer, max, maxPerCycle, 24, 20, 7,
                List.of(EntityType.COW), Set.of());
    }

    @Test
    void singlePlayerUsesBaseTarget() {
        assertEquals(8, WildAnimalBalancer.targetFor(cfg(8, 4, 40, 6), 1));
    }

    @Test
    void eachAdditionalPlayerAddsPerPlayer() {
        assertEquals(12, WildAnimalBalancer.targetFor(cfg(8, 4, 40, 6), 2));
        assertEquals(16, WildAnimalBalancer.targetFor(cfg(8, 4, 40, 6), 3));
    }

    @Test
    void targetIsCappedAtMax() {
        assertEquals(40, WildAnimalBalancer.targetFor(cfg(8, 4, 40, 6), 100));
    }

    @Test
    void zeroPlayersGuardedToBaseTarget() {
        // census never passes 0, but the guard keeps the function total.
        assertEquals(8, WildAnimalBalancer.targetFor(cfg(8, 4, 40, 6), 0));
    }

    @Test
    void spawnCountFillsDeficitUpToCycleCap() {
        assertEquals(6, WildAnimalBalancer.spawnCount(cfg(8, 4, 40, 6), 20, 0));
    }

    @Test
    void spawnCountReturnsExactDeficitWhenBelowCap() {
        assertEquals(3, WildAnimalBalancer.spawnCount(cfg(8, 4, 40, 6), 8, 5));
    }

    @Test
    void spawnCountZeroWhenAtOrAboveTarget() {
        assertEquals(0, WildAnimalBalancer.spawnCount(cfg(8, 4, 40, 6), 8, 8));
        assertEquals(0, WildAnimalBalancer.spawnCount(cfg(8, 4, 40, 6), 8, 12));
    }
}
