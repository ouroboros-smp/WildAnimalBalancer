package com.ouroboros.wildlife;

import net.kyori.adventure.text.Component;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Tameable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Tests the "wild" heuristic: a breedable animal that is not tamed, leashed, or named.
 * Uses Mockito interface mocks so no server is needed.
 */
class WildAnimalPredicateTest {

    @Test
    void plainAnimalIsWild() {
        Animals cow = mock(Animals.class); // not tamed, not leashed, no custom name
        assertTrue(WildAnimalBalancer.isWildAnimal(cow));
    }

    @Test
    void tamedAnimalIsNotWild() {
        Animals horse = mock(Animals.class, withSettings().extraInterfaces(Tameable.class));
        when(((Tameable) horse).isTamed()).thenReturn(true);
        assertFalse(WildAnimalBalancer.isWildAnimal(horse));
    }

    @Test
    void leashedAnimalIsNotWild() {
        Animals cow = mock(Animals.class);
        when(cow.isLeashed()).thenReturn(true);
        assertFalse(WildAnimalBalancer.isWildAnimal(cow));
    }

    @Test
    void nameTaggedAnimalIsNotWild() {
        Animals cow = mock(Animals.class);
        when(cow.customName()).thenReturn(Component.text("Bessie"));
        assertFalse(WildAnimalBalancer.isWildAnimal(cow));
    }

    @Test
    void nonAnimalEntityIsNotWild() {
        Entity entity = mock(Entity.class);
        assertFalse(WildAnimalBalancer.isWildAnimal(entity));
    }
}
