package com.ouroboros.wildlife.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Exhaustively covers the platform-neutral wild-animal ownership decision. */
class WildDecisionTest {
    @Test
    void onlyAnUnownedAnimalIsWild() {
        for (boolean tamed : new boolean[]{false, true}) {
            for (boolean leashed : new boolean[]{false, true}) {
                for (boolean named : new boolean[]{false, true}) {
                    assertEquals(!tamed && !leashed && !named,
                            BalancerMath.isWild(tamed, leashed, named));
                }
            }
        }
    }
}
