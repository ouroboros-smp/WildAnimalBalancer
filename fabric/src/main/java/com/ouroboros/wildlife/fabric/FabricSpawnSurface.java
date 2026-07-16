package com.ouroboros.wildlife.fabric;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Heightmap;

/** Validates the world-specific part of a Fabric wildlife spawn location. */
final class FabricSpawnSurface {
    private FabricSpawnSurface() {}

    /**
     * Returns a valid animal spawn position in one column, or null when the column is unsuitable.
     *
     * @param level server world containing the candidate column
     * @param minSkyLight required sky-light level
     * @param x candidate X coordinate
     * @param z candidate Z coordinate
     * @return the first air block above valid grass, or null
     */
    static BlockPos validSpot(
            ServerLevel level, int minSkyLight, int x, int z) {
        if (!level.hasChunk(x >> 4, z >> 4)) return null;
        int topY = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        BlockPos ground = new BlockPos(x, topY - 1, z);
        BlockPos spawn = ground.above();
        if (!level.getBlockState(ground).is(Blocks.GRASS_BLOCK)) return null;
        if (!level.getBlockState(spawn).isAir()
                || !level.getBlockState(spawn.above()).isAir()) return null;
        if (level.getBrightness(LightLayer.SKY, spawn) < minSkyLight) return null;
        return spawn;
    }
}
