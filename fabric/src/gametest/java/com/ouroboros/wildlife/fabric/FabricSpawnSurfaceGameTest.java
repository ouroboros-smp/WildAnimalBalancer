package com.ouroboros.wildlife.fabric;

import net.fabricmc.fabric.api.gametest.v1.CustomTestMethodInvoker;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.animal.cow.Cow;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;

/** Exercises Fabric's real height map, block state, and census-boundary spawn checks. */
public final class FabricSpawnSurfaceGameTest implements CustomTestMethodInvoker {
    private static final int SCAN_RADIUS = 96;

    @GameTest
    public void acceptsLitGrassWithinTheCensus(GameTestHelper helper) {
        BlockPos ground = prepareSurface(helper, Blocks.GRASS_BLOCK);
        BlockPos expected = ground.above();

        BlockPos actual = FabricSpawnSurface.validSpot(helper.getLevel(), 0, ground.getX(), ground.getZ());

        helper.assertValueEqual(actual, expected, "valid grass surface should be selected");
        helper.succeed();
    }

    @GameTest
    public void rejectsNonGrassSurface(GameTestHelper helper) {
        BlockPos ground = prepareSurface(helper, Blocks.DIRT);

        BlockPos actual = FabricSpawnSurface.validSpot(helper.getLevel(), 0, ground.getX(), ground.getZ());

        helper.assertTrue(actual == null, "non-grass surface must be rejected");
        helper.succeed();
    }

    @GameTest
    public void censusSpansTheFullColumnForAnElevatedPlayer(GameTestHelper helper) {
        BlockPos ground = prepareSurface(helper, Blocks.GRASS_BLOCK);
        BlockPos elevatedCenter = ground.above(129);
        ServerLevel level = helper.getLevel();
        Cow groundCow = helper.spawnWithNoFreeWill(EntityTypes.COW, new BlockPos(1, 11, 1));

        AABB census = FabricCensusVolume.around(level, Vec3.atCenterOf(elevatedCenter), SCAN_RADIUS);

        helper.assertTrue(census.minY == level.getMinY() && census.maxY == level.getMaxY(),
                "census must span the world's full build height");
        helper.runAfterDelay(1, () -> {
            helper.assertTrue(level.getEntities((Entity) null, census, entity -> entity == groundCow)
                            .contains(groundCow),
                    "ground wildlife must be counted for an elevated player");
            helper.succeed();
        });
    }

    @Override
    public void invokeTestMethod(GameTestHelper helper, Method method) throws ReflectiveOperationException {
        method.invoke(this, helper);
    }

    private static BlockPos prepareSurface(GameTestHelper helper, Block block) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(1, 10, 1));
        level.setBlockAndUpdate(ground, block.defaultBlockState());
        level.setBlockAndUpdate(ground.above(), Blocks.AIR.defaultBlockState());
        level.setBlockAndUpdate(ground.above(2), Blocks.AIR.defaultBlockState());
        return ground;
    }
}
