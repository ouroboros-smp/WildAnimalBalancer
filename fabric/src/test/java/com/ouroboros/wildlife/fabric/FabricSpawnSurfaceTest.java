package com.ouroboros.wildlife.fabric;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Verifies each Fabric-specific spawn-surface eligibility rule without a live server. */
class FabricSpawnSurfaceTest {
    private static final int X = 8;
    private static final int Z = 12;
    private static final int TOP_Y = 64;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void rejectsAnUnloadedChunk() {
        ServerLevel level = mock(ServerLevel.class);

        assertNull(FabricSpawnSurface.validSpot(level, 7, X, Z));
    }

    @Test
    void rejectsNonGrassGround() {
        ServerLevel level = preparedLevel();
        when(level.getBlockState(ground())).thenReturn(Blocks.DIRT.defaultBlockState());

        assertNull(FabricSpawnSurface.validSpot(level, 7, X, Z));
    }

    @Test
    void rejectsBlockedHeadroom() {
        ServerLevel level = preparedLevel();
        when(level.getBlockState(spawn())).thenReturn(Blocks.STONE.defaultBlockState());

        assertNull(FabricSpawnSurface.validSpot(level, 7, X, Z));
    }

    @Test
    void rejectsInsufficientSkyLight() {
        ServerLevel level = preparedLevel();
        when(level.getBrightness(LightLayer.SKY, spawn())).thenReturn(6);

        assertNull(FabricSpawnSurface.validSpot(level, 7, X, Z));
    }

    @Test
    void acceptsALoadedLitGrassSurfaceWithinTheCensus() {
        ServerLevel level = preparedLevel();
        when(level.getBrightness(LightLayer.SKY, spawn())).thenReturn(7);

        assertEquals(spawn(), FabricSpawnSurface.validSpot(level, 7, X, Z));
    }

    private static ServerLevel preparedLevel() {
        ServerLevel level = mock(ServerLevel.class);
        when(level.hasChunk(X >> 4, Z >> 4)).thenReturn(true);
        when(level.getHeight(Heightmap.Types.MOTION_BLOCKING, X, Z)).thenReturn(TOP_Y);
        when(level.getBlockState(ground())).thenReturn(Blocks.GRASS_BLOCK.defaultBlockState());
        when(level.getBlockState(spawn())).thenReturn(Blocks.AIR.defaultBlockState());
        when(level.getBlockState(spawn().above())).thenReturn(Blocks.AIR.defaultBlockState());
        return level;
    }

    private static BlockPos ground() {
        return new BlockPos(X, TOP_Y - 1, Z);
    }

    private static BlockPos spawn() {
        return ground().above();
    }
}
