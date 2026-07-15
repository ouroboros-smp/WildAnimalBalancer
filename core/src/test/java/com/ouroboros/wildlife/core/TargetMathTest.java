package com.ouroboros.wildlife.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Pure-logic tests for target math, pools, and anti-farm guardrails. */
class TargetMathTest {
    private static Settings settings(int base, int perPlayer, int max, int maxPerCycle) {
        return new Settings(
                30L, 96, base, perPlayer, max, maxPerCycle, 24, 20, 7,
                3, 30, true,
                List.of("minecraft:cow"), Map.of(), true, Map.of(), Set.of(),
                false, false, 0, false, "127.0.0.1", 9940);
    }

    private static Settings pools(
            List<String> animals,
            Map<String, List<String>> overrides,
            boolean vanillaDefaults,
            Map<String, List<String>> vanilla) {
        return new Settings(
                30L, 96, 8, 4, 40, 6, 24, 20, 7, 3, 30, true,
                animals, overrides, vanillaDefaults, vanilla, Set.of(),
                false, false, 0, false, "127.0.0.1", 9940);
    }

    @Test
    void targetScalesAndCaps() {
        Settings settings = settings(8, 4, 40, 6);
        assertEquals(8, BalancerMath.targetFor(settings, 0));
        assertEquals(8, BalancerMath.targetFor(settings, 1));
        assertEquals(12, BalancerMath.targetFor(settings, 2));
        assertEquals(40, BalancerMath.targetFor(settings, 100));
    }

    @Test
    void spawnCountFillsOnlyTheCappedDeficit() {
        Settings settings = settings(8, 4, 40, 6);
        assertEquals(6, BalancerMath.spawnCount(settings, 20, 0));
        assertEquals(3, BalancerMath.spawnCount(settings, 8, 5));
        assertEquals(0, BalancerMath.spawnCount(settings, 8, 8));
        assertEquals(0, BalancerMath.spawnCount(settings, 8, 12));
    }

    @Test
    void deficitStreakRequiresConsecutiveShortfalls() {
        assertEquals(1, BalancerMath.nextDeficitStreak(0, false, true));
        assertEquals(3, BalancerMath.nextDeficitStreak(2, true, true));
        assertEquals(1, BalancerMath.nextDeficitStreak(2, false, true));
        assertEquals(0, BalancerMath.nextDeficitStreak(2, true, false));
    }

    @Test
    void hourlyBudgetCapsSpawnsAndNeverGoesNegative() {
        Settings settings = settings(8, 4, 40, 6);
        assertEquals(6, BalancerMath.budgetedSpawns(settings, 6, 0));
        assertEquals(2, BalancerMath.budgetedSpawns(settings, 6, 28));
        assertEquals(0, BalancerMath.budgetedSpawns(settings, 6, 30));
        assertEquals(0, BalancerMath.budgetedSpawns(settings, 6, 35));
    }

    @Test
    void biomeOverridesReplaceThePoolOnlyWhereMapped() {
        Settings settings = pools(
                List.of("minecraft:cow"),
                Map.of("snowy_plains", List.of("minecraft:sheep"), "desert", List.of()),
                false, Map.of());
        assertEquals(List.of("minecraft:sheep"), BalancerMath.poolFor(settings, "snowy_plains"));
        assertEquals(List.of(), BalancerMath.poolFor(settings, "desert"));
        assertEquals(List.of("minecraft:cow"), BalancerMath.poolFor(settings, "plains"));
    }

    @Test
    void vanillaDefaultsFilterButNeverExpandThePool() {
        Settings settings = pools(
                List.of("minecraft:cow", "minecraft:pig", "minecraft:chicken"),
                Map.of(), true,
                Map.of(
                        "taiga", List.of("minecraft:cow", "minecraft:chicken", "minecraft:fox"),
                        "snowy_plains", List.of("minecraft:rabbit")));
        assertEquals(List.of("minecraft:cow", "minecraft:chicken"),
                BalancerMath.poolFor(settings, "taiga"));
        assertEquals(List.of(), BalancerMath.poolFor(settings, "snowy_plains"));
        assertEquals(settings.animals(), BalancerMath.poolFor(settings, "terra:alpine_meadow"));
    }

    @Test
    void explicitOverrideBeatsVanillaAndFlagOffDisablesFiltering() {
        Map<String, List<String>> vanilla = Map.of(
                "snowy_plains", List.of("minecraft:rabbit"));
        Settings overridden = pools(
                List.of("minecraft:cow"),
                Map.of("snowy_plains", List.of("minecraft:sheep")), true, vanilla);
        assertEquals(List.of("minecraft:sheep"),
                BalancerMath.poolFor(overridden, "snowy_plains"));

        Settings flagOff = pools(List.of("minecraft:cow"), Map.of(), false, vanilla);
        assertEquals(List.of("minecraft:cow"), BalancerMath.poolFor(flagOff, "snowy_plains"));
    }

    @Test
    void cellKeyIsScopedPerWorld() {
        assertNotEquals(BalancerMath.cellKey("minecraft:overworld", 100, 100),
                BalancerMath.cellKey("minecraft:the_nether", 100, 100));
        assertEquals(BalancerMath.cellKey("minecraft:overworld", 0, 0),
                BalancerMath.cellKey("minecraft:overworld", 127, 127));
        assertNotEquals(BalancerMath.cellKey("minecraft:overworld", 127, 0),
                BalancerMath.cellKey("minecraft:overworld", 128, 0));
        assertNotEquals(BalancerMath.cellKey("minecraft:overworld", -1, 0),
                BalancerMath.cellKey("minecraft:overworld", 0, 0));
    }
}
