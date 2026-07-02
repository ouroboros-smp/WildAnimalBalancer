package com.ouroboros.wildlife;

import org.bukkit.entity.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pure-logic tests for the spawn target math and the anti-farm guardrails.
 * No server required. Mirrors the defaults documented in the README and config.yml.
 */
class TargetMathTest {

    private static WildAnimalBalancer.Settings cfg(int base, int perPlayer, int max, int maxPerCycle) {
        return new WildAnimalBalancer.Settings(
                30L, 96, base, perPlayer, max, maxPerCycle, 24, 20, 7,
                3, 30, true,
                List.of(EntityType.COW), Map.of(), true, Map.of(), Set.of());
    }

    private static WildAnimalBalancer.Settings pools(List<EntityType> animals,
                                                     Map<String, List<EntityType>> overrides,
                                                     boolean vanillaDefaults,
                                                     Map<String, List<EntityType>> vanilla) {
        return new WildAnimalBalancer.Settings(
                30L, 96, 8, 4, 40, 6, 24, 20, 7, 3, 30, true,
                animals, overrides, vanillaDefaults, vanilla, Set.of());
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

    @Test
    void deficitStreakCountsConsecutiveShortfalls() {
        assertEquals(1, WildAnimalBalancer.nextDeficitStreak(0, false, true));
        assertEquals(3, WildAnimalBalancer.nextDeficitStreak(2, true, true));
    }

    @Test
    void deficitStreakRestartsAfterAGapAndClearsOnRecovery() {
        // player left the cell and came back later: not consecutive, streak restarts
        assertEquals(1, WildAnimalBalancer.nextDeficitStreak(2, false, true));
        // the area recovered on its own: streak clears entirely
        assertEquals(0, WildAnimalBalancer.nextDeficitStreak(2, true, false));
    }

    @Test
    void hourlyBudgetCapsSpawnsAndNeverGoesNegative() {
        WildAnimalBalancer.Settings c = cfg(8, 4, 40, 6); // cell-hourly-budget 30
        assertEquals(6, WildAnimalBalancer.budgetedSpawns(c, 6, 0));
        assertEquals(2, WildAnimalBalancer.budgetedSpawns(c, 6, 28));
        assertEquals(0, WildAnimalBalancer.budgetedSpawns(c, 6, 30));
        assertEquals(0, WildAnimalBalancer.budgetedSpawns(c, 6, 35));
    }

    @Test
    void biomeOverridesReplaceThePoolOnlyWhereMapped() {
        WildAnimalBalancer.Settings c = pools(
                List.of(EntityType.COW),
                Map.of("snowy_plains", List.of(EntityType.SHEEP), "desert", List.of()),
                false, Map.of());
        assertEquals(List.of(EntityType.SHEEP), WildAnimalBalancer.poolFor(c, "snowy_plains"));
        assertEquals(List.of(), WildAnimalBalancer.poolFor(c, "desert"));
        assertEquals(List.of(EntityType.COW), WildAnimalBalancer.poolFor(c, "plains"));
    }

    @Test
    void vanillaDefaultsFilterThePoolButNeverExpandIt() {
        WildAnimalBalancer.Settings c = pools(
                List.of(EntityType.COW, EntityType.PIG, EntityType.CHICKEN),
                Map.of(), true,
                Map.of("taiga", List.of(EntityType.COW, EntityType.CHICKEN, EntityType.FOX),
                       "snowy_plains", List.of(EntityType.RABBIT)));
        // narrowed to the intersection, keeping the animals-list order; FOX is not added
        assertEquals(List.of(EntityType.COW, EntityType.CHICKEN), WildAnimalBalancer.poolFor(c, "taiga"));
        // no configured species spawns there in vanilla: biome yields nothing
        assertEquals(List.of(), WildAnimalBalancer.poolFor(c, "snowy_plains"));
        // biome unknown to the snapshot (custom or datapack): unfiltered
        assertEquals(c.animals(), WildAnimalBalancer.poolFor(c, "terra:alpine_meadow"));
    }

    @Test
    void explicitOverrideBeatsVanillaAndFlagOffDisablesFiltering() {
        Map<String, List<EntityType>> vanilla = Map.of("snowy_plains", List.of(EntityType.RABBIT));
        WildAnimalBalancer.Settings overridden = pools(
                List.of(EntityType.COW),
                Map.of("snowy_plains", List.of(EntityType.SHEEP)),
                true, vanilla);
        assertEquals(List.of(EntityType.SHEEP), WildAnimalBalancer.poolFor(overridden, "snowy_plains"));

        WildAnimalBalancer.Settings flagOff = pools(List.of(EntityType.COW), Map.of(), false, vanilla);
        assertEquals(List.of(EntityType.COW), WildAnimalBalancer.poolFor(flagOff, "snowy_plains"));
    }
}
