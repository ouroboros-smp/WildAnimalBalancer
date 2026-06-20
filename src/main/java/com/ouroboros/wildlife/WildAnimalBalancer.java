package com.ouroboros.wildlife;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Folia-aware wild-animal balancer.
 *
 * Runs a periodic census anchored on players (so it only ever touches loaded,
 * region-owned chunks) and tops up wild food animals when an area is below a
 * target that scales with local player count.
 *
 * Folia rules this respects:
 *   - Every entity/world read and the spawn itself runs on the OWNING region
 *     thread. We get there via each player's EntityScheduler, never the global
 *     scheduler. Enumerating entities off the owning thread is a hard error.
 *   - The async driver does no world access at all. It only walks the player
 *     list and dispatches per-player tasks.
 */
public final class WildAnimalBalancer {

    /** Tuning, loaded from config.yml. enabledWorlds empty = every world. */
    public record Settings(
            long cycleSeconds,
            int scanRadius,
            int baseTarget,
            int perPlayer,
            int maxTarget,
            int maxPerCycle,
            int minSpawnDist,
            int spawnTries,
            int minSkyLight,
            List<EntityType> animals,
            Set<String> enabledWorlds
    ) {}

    private final Plugin plugin;
    private final Settings cfg;
    private final Random rng = new Random();

    // coarse ~128-block cell -> cycle it was last handled, so two players sharing
    // a region in the same cycle do not both spawn into the same patch of ground.
    private final ConcurrentHashMap<Long, Integer> handled = new ConcurrentHashMap<>();
    private final AtomicInteger cycle = new AtomicInteger();
    private ScheduledTask driver;

    public WildAnimalBalancer(Plugin plugin, Settings cfg) {
        this.plugin = plugin;
        this.cfg = cfg;
    }

    public void start() {
        // Async driver: no world access here, just dispatch each player to its own region thread.
        driver = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            int c = cycle.incrementAndGet();
            handled.values().removeIf(v -> v < c - 2); // prune stale cells
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                // EntityScheduler.run executes on whatever region currently owns this player.
                // 3rd arg (retired callback) is null: if the player logs off first, just skip.
                p.getScheduler().run(plugin, task -> census(p, c), null);
            }
        }, cfg.cycleSeconds(), cfg.cycleSeconds(), TimeUnit.SECONDS);
    }

    public void stop() {
        if (driver != null) driver.cancel();
    }

    /**
     * Target wild-animal count for an area, given how many players share it.
     * Pure function, extracted so it can be unit tested without a server.
     * localPlayers is expected to be at least 1 (the anchor player).
     */
    static int targetFor(Settings cfg, int localPlayers) {
        int extra = Math.max(0, localPlayers - 1);
        return Math.min(cfg.maxTarget(), cfg.baseTarget() + cfg.perPlayer() * extra);
    }

    /**
     * How many animals to spawn this cycle given the target and current wild count,
     * throttled by the per-cycle cap. Returns 0 when the area is already at or above
     * target. Pure function, extracted for unit testing.
     */
    static int spawnCount(Settings cfg, int target, int wild) {
        int deficit = target - wild;
        if (deficit <= 0) return 0;
        return Math.min(deficit, cfg.maxPerCycle());
    }

    /** Runs on the player's owning region thread. Safe to read and spawn entities here. */
    private void census(Player player, int currentCycle) {
        if (!player.isOnline()) return;
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) return;
        if (!cfg.enabledWorlds().isEmpty() && !cfg.enabledWorlds().contains(world.getName())) return;

        // Dedupe: first player to claim this ~128-block cell this cycle proceeds, others bail.
        // ConcurrentHashMap.put returns the previous value atomically, so exactly one wins.
        long cell = (((long) (at.getBlockX() >> 7)) << 32) ^ ((at.getBlockZ() >> 7) & 0xffffffffL);
        Integer was = handled.put(cell, currentCycle);
        if (was != null && was == currentCycle) return;

        int wild = 0;
        int localPlayers = 1; // the anchor player (getNearbyEntities does not include self)
        int r = cfg.scanRadius();
        for (Entity e : player.getNearbyEntities(r, r, r)) {
            try {
                if (e instanceof Player) { localPlayers++; continue; }
                if (isWildAnimal(e)) wild++;
            } catch (Throwable ignored) {
                // entity owned by a neighbouring region (boundary): skip rather than trip the check
            }
        }

        int target = targetFor(cfg, localPlayers);
        int toSpawn = spawnCount(cfg, target, wild);
        if (toSpawn <= 0) return;

        List<EntityType> pool = cfg.animals();
        for (int i = 0; i < toSpawn; i++) {
            Location spot = findSpawnSpot(at);
            if (spot == null) continue;
            EntityType type = pool.get(rng.nextInt(pool.size()));
            try {
                world.spawnEntity(spot, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            } catch (Throwable ignored) {
                // spot resolved into another region between find and spawn: skip
            }
        }
    }

    /**
     * "Wild" = a breedable animal that does not look like it belongs to a player.
     * The API has no farm flag, so this is a heuristic: not tamed, not leashed, not named.
     * If you run a claims plugin, add a claim check here to also exclude claimed animals.
     *
     * Package-private static so it can be unit tested directly.
     */
    static boolean isWildAnimal(Entity e) {
        if (!(e instanceof Animals animal)) return false;       // cows/pigs/sheep/chickens/etc.
        if (animal instanceof Tameable t && t.isTamed()) return false;
        if (animal.isLeashed()) return false;
        if (animal.customName() != null) return false;          // name-tagged: someone owns it
        return true;
    }

    /** Find a sensible surface spot near the player, on a loaded chunk this region owns. */
    private Location findSpawnSpot(Location center) {
        World w = center.getWorld();
        if (w == null) return null;
        int min = cfg.minSpawnDist();
        int max = cfg.scanRadius();
        for (int i = 0; i < cfg.spawnTries(); i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double dist = min + rng.nextDouble() * (max - min);
            int x = center.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);

            if (!w.isChunkLoaded(x >> 4, z >> 4)) continue; // unloaded, or not our region's chunk
            try {
                int y = w.getHighestBlockYAt(x, z);
                Block ground = w.getBlockAt(x, y, z);
                if (ground.getType() != Material.GRASS_BLOCK) continue; // keep it on grassland
                Block above = w.getBlockAt(x, y + 1, z);
                Block head = w.getBlockAt(x, y + 2, z);
                if (!above.getType().isAir() || !head.getType().isAir()) continue; // need clearance
                if (above.getLightFromSky() < cfg.minSkyLight()) continue; // no caves / canopy
                return new Location(w, x + 0.5, y + 1, z + 0.5);
            } catch (Throwable ignored) {
                // chunk belongs to a neighbouring region: skip this candidate
            }
        }
        return null;
    }
}
