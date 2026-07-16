package com.ouroboros.wildlife.fabric;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies that wildlife census volumes include the world's entire build height. */
class FabricCensusVolumeTest {
    @Test
    void spansTheFullBuildHeightRegardlessOfPlayerElevation() {
        AABB volume = FabricCensusVolume.around(
                new Vec3(12.5, 250.0, -8.5), 96, -64, 320);

        assertEquals(-64.0, volume.minY);
        assertEquals(320.0, volume.maxY);
        assertTrue(volume.contains(12.5, -63.5, -8.5));
        assertTrue(volume.contains(12.5, 319.5, -8.5));
    }
}
