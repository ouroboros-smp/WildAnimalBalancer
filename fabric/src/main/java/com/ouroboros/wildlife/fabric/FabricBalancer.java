package com.ouroboros.wildlife.fabric;

import com.ouroboros.wildlife.core.BalancerMath;
import com.ouroboros.wildlife.core.BalancerStats;
import com.ouroboros.wildlife.core.CensusOutcome;
import com.ouroboros.wildlife.core.Settings;
import com.ouroboros.wildlife.core.SpawnLogger;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.phys.AABB;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Single-threaded Fabric census engine with player work spread across each cycle. */
final class FabricBalancer {
    private static final int GROUP_RADIUS = 4;
    private static final long BUDGET_WINDOW_MILLIS = TimeUnit.HOURS.toMillis(1);

    private final Settings settings;
    private final BalancerStats stats;
    private final Consumer<String> spawnLogSink;
    private final Map<String, EntityType<?>> entityTypes;
    private final Random random = new Random();
    private final Queue<UUID> pending = new ArrayDeque<>();
    private final ConcurrentHashMap<String, Integer> handled = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CellStreak> deficits = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CellBudget> budgets = new ConcurrentHashMap<>();
    private final long cycleTicks;
    private long tickCounter;
    private int cycle;
    private int lastWarnedCycle = -1;

    private record CellStreak(int cycle, int streak) {}
    private record CellBudget(long windowStart, int spawned) {}
    private record SpawnOutcome(int spawned, EntityType<?> species, String biome, BlockPos anchor) {}

    FabricBalancer(Settings settings, BalancerStats stats, Consumer<String> spawnLogSink) {
        this.settings = settings;
        this.stats = stats;
        this.spawnLogSink = spawnLogSink;
        this.entityTypes = resolveEntityTypes(settings);
        this.cycleTicks = settings.cycleSeconds() > Long.MAX_VALUE / 20
                ? Long.MAX_VALUE : settings.cycleSeconds() * 20;
    }

    void tick(MinecraftServer server) {
        long tickInCycle = tickCounter % cycleTicks;
        if (tickInCycle == 0) beginCycle(server);
        drainPlayers(server, cycleTicks - tickInCycle);
        tickCounter++;
    }

    BalancerStats.Gauges gauges() {
        int onStreak = 0;
        for (CellStreak streak : deficits.values()) {
            if (streak.streak() > 0) onStreak++;
        }
        long cutoff = System.currentTimeMillis() - BUDGET_WINDOW_MILLIS;
        int budgetSpent = 0;
        for (CellBudget budget : budgets.values()) {
            if (budget.windowStart() >= cutoff
                    && budget.spawned() >= settings.cellHourlyBudget()) {
                budgetSpent++;
            }
        }
        return new BalancerStats.Gauges(handled.size(), onStreak, budgetSpent);
    }

    private void beginCycle(MinecraftServer server) {
        cycle++;
        handled.values().removeIf(value -> value < cycle - 2);
        deficits.values().removeIf(value -> value.cycle() < cycle - 2);
        long cutoff = System.currentTimeMillis() - BUDGET_WINDOW_MILLIS;
        budgets.values().removeIf(value -> value.windowStart() < cutoff);
        if (settings.statusLogCycles() > 0 && cycle % settings.statusLogCycles() == 0) {
            WildlifeMod.LOGGER.info(BalancerStats.summaryLine(stats.snapshot(), gauges()));
        }
        pending.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            pending.add(player.getUUID());
        }
    }

    private void drainPlayers(MinecraftServer server, long ticksLeft) {
        if (pending.isEmpty()) return;
        int count = (int) Math.max(1L, (pending.size() + ticksLeft - 1) / ticksLeft);
        for (int i = 0; i < count; i++) {
            UUID playerId = pending.poll();
            if (playerId == null) return;
            ServerPlayer player = server.getPlayerList().getPlayer(playerId);
            if (player == null) {
                BalancerMath.recordCensusOutcome(stats, CensusOutcome.SKIPPED_GONE);
                continue;
            }
            census(player);
        }
    }

    private void census(ServerPlayer player) {
        try {
            BalancerMath.recordCensusOutcome(stats, runCensus(player));
        } catch (Exception exception) {
            stats.censusFailed();
            if (lastWarnedCycle != cycle) {
                lastWarnedCycle = cycle;
                WildlifeMod.LOGGER.warn("census failed for {}", player.getName().getString(), exception);
            }
        }
    }

    private CensusOutcome runCensus(ServerPlayer player) {
        ServerLevel level = player.level();
        String worldId = level.dimension().identifier().toString();
        if (!settings.enabledWorlds().isEmpty()
                && !settings.enabledWorlds().contains(worldId)) {
            return CensusOutcome.SKIPPED_DISABLED_WORLD;
        }

        BlockPos playerPos = player.blockPosition();
        String cell = BalancerMath.cellKey(worldId, playerPos.getX(), playerPos.getZ());
        Integer previousClaim = handled.put(cell, cycle);
        if (previousClaim != null && previousClaim == cycle) {
            return CensusOutcome.SKIPPED_SHARED_CELL;
        }

        int wild = 0;
        int localPlayers = 1;
        AABB box = FabricCensusVolume.around(level, player.position(), settings.scanRadius());
        for (Entity entity : level.getEntities(
                player, box, entity -> entity instanceof ServerPlayer || isWildAnimal(entity))) {
            if (entity instanceof ServerPlayer) localPlayers++;
            else wild++;
        }

        int target = BalancerMath.targetFor(settings, localPlayers);
        int deficit = BalancerMath.spawnCount(settings, target, wild);
        CellStreak previous = deficits.get(cell);
        int streak = BalancerMath.nextDeficitStreak(
                previous == null ? 0 : previous.streak(),
                previous != null && previous.cycle() == cycle - 1,
                deficit > 0);
        deficits.put(cell, new CellStreak(cycle, streak));

        long now = System.currentTimeMillis();
        CellBudget budget = currentBudget(cell, now);
        if (deficit <= 0) {
            return completeCensus(player, wild, target, streak, budgetLeft(budget));
        }
        stats.deficitObserved();
        if (streak < settings.deficitCycles()) {
            stats.blockedByStreak();
            return completeCensus(player, wild, target, streak, budgetLeft(budget));
        }

        int toSpawn = BalancerMath.budgetedSpawns(settings, deficit, budget.spawned());
        if (toSpawn <= 0) {
            stats.blockedByBudget();
            return completeCensus(player, wild, target, streak, 0);
        }

        SpawnOutcome outcome = spawnGroup(level, playerPos, toSpawn);
        if (outcome == null || outcome.spawned() <= 0) {
            return completeCensus(player, wild, target, streak, budgetLeft(budget));
        }
        int spentNow = budget.spawned() + outcome.spawned();
        CellBudget updatedBudget = new CellBudget(budget.windowStart(), spentNow);
        budgets.put(cell, updatedBudget);

        Identifier speciesId = BuiltInRegistries.ENTITY_TYPE.getKey(outcome.species());
        String species = speciesId.getNamespace().equals("minecraft")
                ? speciesId.getPath() : speciesId.toString();
        stats.spawned(worldId, species, outcome.spawned());
        int budgetLeft = budgetLeft(updatedBudget);
        if (settings.logSpawns()) {
            WildlifeMod.LOGGER.info(
                    "Topped up {} ({}, {}, {}) biome {}: +{} {}, wild {}/{}, players {}, streak {}, hourly budget left {}",
                    SpawnLogger.escape(worldId), outcome.anchor().getX(), outcome.anchor().getY(),
                    outcome.anchor().getZ(), SpawnLogger.escape(outcome.biome()), outcome.spawned(),
                    species, wild, target, localPlayers, streak, budgetLeft);
        }
        if (spawnLogSink != null) {
            spawnLogSink.accept(SpawnLogger.json(
                    now, worldId, cell, outcome.biome(), species, outcome.spawned(), wild,
                    target, localPlayers, streak, budgetLeft, outcome.anchor().getX(),
                    outcome.anchor().getY(), outcome.anchor().getZ()));
        }
        return completeCensus(player, wild, target, streak, budgetLeft);
    }

    private CensusOutcome completeCensus(
            ServerPlayer player, int wild, int target, int streak, int budgetLeft) {
        if (ServerPlayNetworking.canSend(player, BalancerHudPayload.TYPE)
                && Permissions.check(player, "wildlife.hud", PermissionLevel.GAMEMASTERS)) {
            int seconds = (int) Math.min(Integer.MAX_VALUE, settings.cycleSeconds());
            ServerPlayNetworking.send(player,
                    new BalancerHudPayload(wild, target, streak, budgetLeft, seconds));
        }
        return CensusOutcome.RAN;
    }

    private CellBudget currentBudget(String cell, long now) {
        CellBudget budget = budgets.get(cell);
        if (budget == null || now - budget.windowStart() >= BUDGET_WINDOW_MILLIS) {
            return new CellBudget(now, 0);
        }
        return budget;
    }

    private int budgetLeft(CellBudget budget) {
        return Math.max(0, settings.cellHourlyBudget() - budget.spawned());
    }

    private SpawnOutcome spawnGroup(ServerLevel level, BlockPos center, int count) {
        BlockPos anchor = findSpawnSpot(level, center);
        if (anchor == null) {
            stats.noSpawnSpot();
            return null;
        }
        String biome = level.getBiome(anchor).unwrapKey()
                .map(key -> key.identifier().getPath())
                .orElse("unknown");
        List<String> pool = BalancerMath.poolFor(settings, biome);
        if (pool.isEmpty()) {
            stats.emptyBiomePool();
            return null;
        }
        EntityType<?> type = entityTypes.get(pool.get(random.nextInt(pool.size())));
        if (type == null) {
            stats.emptyBiomePool();
            return null;
        }

        int spawned = 0;
        for (int i = 0; i < count; i++) {
            BlockPos spot = jitterNear(level, center, anchor);
            Entity entity = type.spawn(level, spot, EntitySpawnReason.MOB_SUMMONED);
            if (entity == null) continue;
            if (settings.persistentSpawns() && entity instanceof Mob mob) {
                mob.setPersistenceRequired();
            }
            spawned++;
        }
        return new SpawnOutcome(spawned, type, biome, anchor);
    }

    private BlockPos findSpawnSpot(ServerLevel level, BlockPos center) {
        int min = settings.minSpawnDist();
        int max = Math.max(settings.scanRadius(), min);
        for (int i = 0; i < settings.spawnTries(); i++) {
            double angle = random.nextDouble() * Math.PI * 2;
            double distance = min + random.nextDouble() * (max - min);
            int x = center.getX() + (int) Math.round(Math.cos(angle) * distance);
            int z = center.getZ() + (int) Math.round(Math.sin(angle) * distance);
            BlockPos spot = validSurfaceSpot(level, x, z);
            if (spot != null) return spot;
        }
        return null;
    }

    private BlockPos jitterNear(ServerLevel level, BlockPos center, BlockPos anchor) {
        long min = settings.minSpawnDist();
        for (int i = 0; i < 4; i++) {
            int x = anchor.getX() + random.nextInt(GROUP_RADIUS * 2 + 1) - GROUP_RADIUS;
            int z = anchor.getZ() + random.nextInt(GROUP_RADIUS * 2 + 1) - GROUP_RADIUS;
            long dx = x - center.getX();
            long dz = z - center.getZ();
            if (dx * dx + dz * dz < min * min) continue;
            BlockPos spot = validSurfaceSpot(level, x, z);
            if (spot != null) return spot;
        }
        return anchor;
    }

    private BlockPos validSurfaceSpot(ServerLevel level, int x, int z) {
        return FabricSpawnSurface.validSpot(level, settings.minSkyLight(), x, z);
    }

    private static boolean isWildAnimal(Entity entity) {
        if (!(entity instanceof Animal)) return false;
        boolean tamed = entity instanceof TamableAnimal tamable && tamable.isTame();
        if (entity instanceof AbstractHorse horse && horse.isTamed()) tamed = true;
        boolean leashed = entity instanceof Leashable leashable && leashable.isLeashed();
        return BalancerMath.isWild(tamed, leashed, entity.getCustomName() != null);
    }

    private static Map<String, EntityType<?>> resolveEntityTypes(Settings settings) {
        List<String> ids = new ArrayList<>(settings.animals());
        settings.biomeAnimals().values().forEach(ids::addAll);
        Map<String, EntityType<?>> resolved = new HashMap<>();
        for (String id : ids) {
            Identifier identifier = Identifier.tryParse(id);
            if (identifier == null) continue;
            BuiltInRegistries.ENTITY_TYPE.getOptional(identifier)
                    .ifPresent(type -> resolved.put(id, type));
        }
        return Map.copyOf(resolved);
    }
}
