package com.ouroboros.wildlife.fabric;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Builds the full-height volume used to census wildlife around a player. */
final class FabricCensusVolume {
    private FabricCensusVolume() {}

    /**
     * Returns the world's complete vertical build range inside a horizontal census footprint.
     *
     * <p>This keeps animals on natural ground and elevated player platforms in one shared,
     * capped population. The volume deliberately does not depend on the player's Y coordinate.
     *
     * @param level server world being censused
     * @param center player position that anchors the horizontal footprint
     * @param horizontalRadius horizontal census radius
     * @return a box spanning the world's complete build height
     */
    static AABB around(ServerLevel level, Vec3 center, int horizontalRadius) {
        return around(center, horizontalRadius, level.getMinY(), level.getMaxY());
    }

    /**
     * Returns a full-height census volume for known world-height bounds.
     *
     * @param center player position that anchors the horizontal footprint
     * @param horizontalRadius horizontal census radius
     * @param minY inclusive world minimum build height
     * @param maxY exclusive world maximum build height
     * @return a box spanning the supplied complete build height
     */
    static AABB around(Vec3 center, int horizontalRadius, int minY, int maxY) {
        return new AABB(
                center.x - horizontalRadius, minY, center.z - horizontalRadius,
                center.x + horizontalRadius, maxY, center.z + horizontalRadius);
    }
}
