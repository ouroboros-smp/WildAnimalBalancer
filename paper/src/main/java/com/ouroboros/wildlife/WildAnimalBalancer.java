package com.ouroboros.wildlife;

import com.ouroboros.wildlife.core.BalancerMath;
import com.ouroboros.wildlife.core.BalancerStats;
import com.ouroboros.wildlife.core.CensusOutcome;
import com.ouroboros.wildlife.core.Settings;
import com.ouroboros.wildlife.core.SpawnLogger;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

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
 *   - Folia demands the current region own EVERY chunk a getNearbyEntities box
 *     touches, and it logs at ERROR before throwing, so a catch cannot keep the
 *     log clean. The census therefore pre-checks ownership of the whole scan box
 *     (Bukkit.isOwnedByCurrentRegion with a covering chunk radius) and skips the
 *     player's census for the cycle when the box crosses a region boundary:
 *     skipped, never forced. Spawn-spot chunks are re-checked individually.
 *   - The async driver does no world access at all. It only walks the player
 *     list and dispatches per-player tasks.
 *
 * Anti-farm guardrails, so the balancer never becomes a meat faucet:
 *   - A shortage must persist for deficit-cycles consecutive censused cycles
 *     before any top-up happens. A just-slaughtered herd does not refill on
 *     the spot, and a player passing through an area triggers nothing.
 *   - Each ~128-block cell has an hourly spawn budget (cell-hourly-budget).
 *     Once spent, the cell refills no further until the window rolls over.
 */
public final class WildAnimalBalancer {
    /** Blocks of scatter around the group anchor so a top-up reads as a herd. */
    private static final int GROUP_RADIUS = 4;
    private static final long BUDGET_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final Plugin plugin;
    private final Settings cfg;
    private final BalancerStats stats;
    private final Map<String, EntityType> entityTypes;
    // JSONL audit sink, null when spawn-log-file is off. Accepts a prebuilt line
    // and must never block (SpawnLogger hands writes to its own IO thread).
    private final Consumer<String> spawnLogSink;
    private final Random rng = new Random();

    // All three maps are keyed by the per-world ~128-block cell (see cellKey).
    // handled: cycle the cell was last claimed, so two players sharing a cell in
    // the same cycle do not both spawn into the same patch of ground.
    private final ConcurrentHashMap<String, Integer> handled = new ConcurrentHashMap<>();
    // deficits: how many consecutive censused cycles the cell has been short.
    private final ConcurrentHashMap<String, CellStreak> deficits = new ConcurrentHashMap<>();
    // budgets: spawns already spent in the cell's current hourly window.
    private final ConcurrentHashMap<String, CellBudget> budgets = new ConcurrentHashMap<>();
    private final AtomicInteger cycle = new AtomicInteger();
    private final AtomicInteger lastWarnedCycle = new AtomicInteger(-1);
    private ScheduledTask driver;

    private record CellStreak(int cycle, int streak) {}
    private record CellBudget(long windowStart, int spawned) {}

    public WildAnimalBalancer(Plugin plugin, Settings cfg, BalancerStats stats, Consumer<String> spawnLogSink) {
        this.plugin = plugin;
        this.cfg = cfg;
        this.stats = stats;
        this.spawnLogSink = spawnLogSink;
        this.entityTypes = resolveEntityTypes(cfg);
    }

    public void start() {
        // Async driver: no world access here, just dispatch each player to its own region thread.
        driver = Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> {
            int c = cycle.incrementAndGet();
            handled.values().removeIf(v -> v < c - 2); // prune stale cells
            deficits.values().removeIf(v -> v.cycle() < c - 2);
            long cutoff = System.currentTimeMillis() - BUDGET_WINDOW_MILLIS;
            budgets.values().removeIf(b -> b.windowStart() < cutoff);
            // after pruning, so the logged gauges reflect live cells, not stale ones
            if (cfg.statusLogCycles() > 0 && c % cfg.statusLogCycles() == 0) {
                plugin.getLogger().info(BalancerStats.summaryLine(stats.snapshot(), gauges()));
            }
            for (Player p : new ArrayList<>(Bukkit.getOnlinePlayers())) {
                // EntityScheduler.run executes on whatever region currently owns this player.
                // 3rd arg (retired callback) is null: if the player logs off first, just skip.
                p.getScheduler().run(plugin, task -> census(p, c), null);
            }
        }, cfg.cycleSeconds(), cfg.cycleSeconds(), TimeUnit.SECONDS); // parseSettings clamps to >= 1
    }

    public void stop() {
        if (driver != null) driver.cancel();
    }

    /**
     * Runs on the player's owning region thread. Backstop wrapper only: ownership
     * is pre-checked in runCensus, so this should never fire. If it does, a real
     * bug is being skipped; log it visibly (WARNING, at most once per cycle so a
     * repeating fault cannot flood the log). Errors (OOM and friends) propagate.
     */
    private void census(Player player, int currentCycle) {
        try {
            BalancerMath.recordCensusOutcome(stats, runCensus(player, currentCycle));
        } catch (Exception e) {
            stats.censusFailed();
            if (lastWarnedCycle.getAndSet(currentCycle) != currentCycle) {
                plugin.getLogger().log(Level.WARNING, "census failed for " + player.getName(), e);
            }
        }
    }

    private CensusOutcome runCensus(Player player, int currentCycle) {
        if (!player.isOnline()) return CensusOutcome.SKIPPED_GONE;
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world == null) return CensusOutcome.SKIPPED_GONE;
        if (!cfg.enabledWorlds().isEmpty() && !cfg.enabledWorlds().contains(world.getName())) {
            return CensusOutcome.SKIPPED_DISABLED_WORLD;
        }

        // Folia requires the current region to own EVERY chunk the census box
        // touches, and its thread check logs at ERROR before throwing, so this
        // cannot be handled after the fact. Pre-check the whole box and skip the
        // player's census this cycle at a boundary: skipped, never forced. This
        // runs BEFORE the cell claim so a skipped player neither blocks the cell
        // for co-located players nor resets the deficit streak. On Paper the
        // check is always true on the main thread.
        if (!Bukkit.isOwnedByCurrentRegion(at, scanChunkRadius(cfg.scanRadius()))) {
            return CensusOutcome.SKIPPED_REGION_BOUNDARY;
        }

        // Dedupe: first player to claim this ~128-block cell this cycle proceeds, others bail.
        // Keyed per world so same-coordinate players in different worlds never collide.
        // ConcurrentHashMap.put returns the previous value atomically, so exactly one wins.
        String cell = BalancerMath.cellKey(world.getUID().toString(), at.getBlockX(), at.getBlockZ());
        Integer was = handled.put(cell, currentCycle);
        if (was != null && was == currentCycle) {
            return CensusOutcome.SKIPPED_SHARED_CELL;
        }

        int wild = 0;
        int localPlayers = 1; // the anchor player (getNearbyEntities does not include self)
        int r = cfg.scanRadius();
        // The box ownership pre-check above guarantees every entity returned here
        // is owned by this region; no per-entity ownership filtering is needed.
        for (Entity e : player.getNearbyEntities(r, r, r)) {
            if (e instanceof Player) { localPlayers++; continue; }
            if (isWildAnimal(e)) wild++;
        }

        int target = BalancerMath.targetFor(cfg, localPlayers);
        int deficit = BalancerMath.spawnCount(cfg, target, wild);

        // Guardrail: the shortage must persist before it is refilled. The streak
        // only advances while someone is here to census the cell, so this doubles
        // as a dwell requirement; passers-by never build one up.
        CellStreak prev = deficits.get(cell);
        int streak = BalancerMath.nextDeficitStreak(prev == null ? 0 : prev.streak(),
                prev != null && prev.cycle() == currentCycle - 1, deficit > 0);
        deficits.put(cell, new CellStreak(currentCycle, streak));
        if (deficit <= 0) return CensusOutcome.RAN;
        stats.deficitObserved();
        if (streak < cfg.deficitCycles()) {
            stats.blockedByStreak();
            return CensusOutcome.RAN;
        }

        // Guardrail: hourly per-cell budget. Camping a field and re-killing it
        // stops paying out once the window is spent.
        long now = System.currentTimeMillis();
        CellBudget budget = budgets.get(cell);
        if (budget == null || now - budget.windowStart() >= BUDGET_WINDOW_MILLIS) budget = new CellBudget(now, 0);
        int toSpawn = BalancerMath.budgetedSpawns(cfg, deficit, budget.spawned());
        if (toSpawn <= 0) {
            stats.blockedByBudget();
            return CensusOutcome.RAN;
        }

        SpawnOutcome out = spawnGroup(at, toSpawn);
        if (out == null || out.spawned() <= 0) return CensusOutcome.RAN; // spawnGroup already counted the failure
        int spentNow = budget.spawned() + out.spawned();
        budgets.put(cell, new CellBudget(budget.windowStart(), spentNow));

        String species = out.species().name().toLowerCase(Locale.ROOT);
        stats.spawned(world.getName(), species, out.spawned());
        int budgetLeft = Math.max(0, cfg.cellHourlyBudget() - spentNow);
        if (cfg.logSpawns()) {
            // world and biome names are external strings; escape control characters
            // so a hostile name cannot forge extra console log lines
            plugin.getLogger().info("Topped up " + SpawnLogger.escape(world.getName())
                    + " (" + out.anchor().getBlockX() + ", " + out.anchor().getBlockY() + ", " + out.anchor().getBlockZ() + ")"
                    + " biome " + SpawnLogger.escape(out.biome()) + ": +" + out.spawned() + " " + species
                    + ", wild " + wild + "/" + target
                    + ", players " + localPlayers
                    + ", streak " + streak
                    + ", hourly budget left " + budgetLeft);
        }
        if (spawnLogSink != null) {
            spawnLogSink.accept(SpawnLogger.json(now, world.getName(), cell, out.biome(), species,
                    out.spawned(), wild, target, localPlayers, streak, budgetLeft,
                    out.anchor().getBlockX(), out.anchor().getBlockY(), out.anchor().getBlockZ()));
        }
        return CensusOutcome.RAN;
    }

    /**
     * Live sizes of the guardrail maps, for /wildlife status, the periodic
     * summary, and the metrics endpoint. Reads only concurrent maps, so it is
     * safe to call from any thread; it never touches world state.
     */
    BalancerStats.Gauges gauges() {
        int onStreak = 0;
        for (CellStreak s : deficits.values()) {
            if (s.streak() > 0) onStreak++;
        }
        long cutoff = System.currentTimeMillis() - BUDGET_WINDOW_MILLIS;
        int budgetSpent = 0;
        for (CellBudget b : budgets.values()) {
            if (b.windowStart() >= cutoff && b.spawned() >= cfg.cellHourlyBudget()) budgetSpent++;
        }
        return new BalancerStats.Gauges(handled.size(), onStreak, budgetSpent);
    }

    /**
     * Per-world ~128-block cell key for the dedupe and guardrail maps. Static and
     * world-id based so the world scoping is unit testable without a server.
     */
    /** Chunk radius covering the census box for the Folia ownership check. */
    static int scanChunkRadius(int scanRadius) {
        return (scanRadius >> 4) + 1;
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
        boolean tamed = animal instanceof Tameable tameable && tameable.isTamed();
        return BalancerMath.isWild(tamed, animal.isLeashed(), animal.customName() != null);
    }

    /** What a top-up actually did, so the caller can count and log it with full context. */
    private record SpawnOutcome(int spawned, EntityType species, String biome, Location anchor) {}

    /**
     * Spawns one same-species group around a single anchor spot, like a worldgen
     * herd, instead of scattering singles across the whole ring. The species is
     * drawn from the biome override for the anchor's biome when one is configured.
     * Returns what spawned, or null when no valid spot or species pool exists
     * (counted here, since only this method knows which one failed).
     */
    private SpawnOutcome spawnGroup(Location center, int count) {
        World w = center.getWorld();
        Location anchor = findSpawnSpot(center);
        if (anchor == null) {
            stats.noSpawnSpot();
            return null;
        }
        String biome = anchor.getBlock().getBiome().getKey().getKey().toLowerCase(Locale.ROOT);
        List<String> pool = BalancerMath.poolFor(cfg, biome);
        if (pool.isEmpty()) { // biome explicitly mapped to no spawns
            stats.emptyBiomePool();
            return null;
        }

        EntityType type = entityTypes.get(pool.get(rng.nextInt(pool.size())));
        if (type == null) {
            stats.emptyBiomePool();
            return null;
        }
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            Location spot = jitterNear(center, anchor);
            Entity e = w.spawnEntity(spot, type, CreatureSpawnEvent.SpawnReason.CUSTOM);
            // Like naturally generated passive animals, top-ups should stay put and
            // repopulate the area, not evaporate when the player wanders off.
            if (cfg.persistentSpawns() && e instanceof LivingEntity le) le.setRemoveWhenFarAway(false);
            spawned++;
        }
        return new SpawnOutcome(spawned, type, biome, anchor);
    }

    private static Map<String, EntityType> resolveEntityTypes(Settings settings) {
        Map<String, EntityType> resolved = new HashMap<>();
        List<String> ids = new ArrayList<>(settings.animals());
        settings.biomeAnimals().values().forEach(ids::addAll);
        for (String id : ids) {
            NamespacedKey key = NamespacedKey.fromString(id);
            if (key == null) continue;
            EntityType type = Registry.ENTITY_TYPE.get(key);
            if (type != null) resolved.put(id, type);
        }
        return Map.copyOf(resolved);
    }

    /** Find a sensible surface spot near the player, on a loaded chunk this region owns. */
    private Location findSpawnSpot(Location center) {
        World w = center.getWorld();
        if (w == null) return null;
        int min = cfg.minSpawnDist();
        int max = Math.max(cfg.scanRadius(), min); // a min beyond the radius pins spawns to the ring edge
        for (int i = 0; i < cfg.spawnTries(); i++) {
            double ang = rng.nextDouble() * Math.PI * 2;
            double dist = min + rng.nextDouble() * (max - min);
            int x = center.getBlockX() + (int) Math.round(Math.cos(ang) * dist);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(ang) * dist);
            Location spot = validSurfaceSpot(w, x, z);
            if (spot != null) return spot;
        }
        return null;
    }

    /** Small scatter around the group anchor, still outside min-spawn-distance. */
    private Location jitterNear(Location center, Location anchor) {
        World w = anchor.getWorld();
        long min = cfg.minSpawnDist();
        for (int i = 0; i < 4; i++) {
            int x = anchor.getBlockX() + rng.nextInt(GROUP_RADIUS * 2 + 1) - GROUP_RADIUS;
            int z = anchor.getBlockZ() + rng.nextInt(GROUP_RADIUS * 2 + 1) - GROUP_RADIUS;
            long dx = x - center.getBlockX();
            long dz = z - center.getBlockZ();
            if (dx * dx + dz * dz < min * min) continue; // stay outside min-spawn-distance
            Location spot = validSurfaceSpot(w, x, z);
            if (spot != null) return spot;
        }
        return anchor; // anchor is already validated; co-located spawns push apart
    }

    /**
     * The grassland rules: region-owned and loaded chunk, grass block, clearance,
     * sky light. Validation and the subsequent spawnEntity run in the same region
     * tick, so ownership cannot change between the check here and the spawn.
     */
    private Location validSurfaceSpot(World w, int x, int z) {
        // Ownership first: never touch a chunk a neighbouring region owns.
        if (!Bukkit.isOwnedByCurrentRegion(w, x >> 4, z >> 4)) return null;
        if (!w.isChunkLoaded(x >> 4, z >> 4)) return null; // spawning outside loaded chunks does nothing
        int y = w.getHighestBlockYAt(x, z);
        Block ground = w.getBlockAt(x, y, z);
        if (ground.getType() != Material.GRASS_BLOCK) return null; // keep it on grassland
        Block above = w.getBlockAt(x, y + 1, z);
        Block head = w.getBlockAt(x, y + 2, z);
        if (!above.getType().isAir() || !head.getType().isAir()) return null; // need clearance
        if (above.getLightFromSky() < cfg.minSkyLight()) return null; // no caves / canopy
        return new Location(w, x + 0.5, y + 1, z + 0.5);
    }
}
