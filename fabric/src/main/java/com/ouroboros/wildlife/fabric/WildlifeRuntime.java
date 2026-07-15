package com.ouroboros.wildlife.fabric;

import com.ouroboros.wildlife.core.BalancerStats;
import com.ouroboros.wildlife.core.MetricsServer;
import com.ouroboros.wildlife.core.Settings;
import com.ouroboros.wildlife.core.SpawnLogger;
import com.ouroboros.wildlife.core.WildlifeConfig;
import com.ouroboros.wildlife.core.YamlSection;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Owns one Fabric server's balancer, monitoring outputs, and live reloads. */
final class WildlifeRuntime {
    private final MinecraftServer server;
    private final Path configDirectory;
    private final Path configFile;
    private final BalancerStats stats = new BalancerStats();
    private final AtomicLong reloadGeneration = new AtomicLong();
    private volatile FabricBalancer balancer;
    private MetricsServer metrics;
    private SpawnLogger spawnLogger;
    private boolean stopped;

    WildlifeRuntime(MinecraftServer server) {
        this.server = server;
        this.configDirectory = FabricLoader.getInstance().getConfigDir().resolve("wildanimalbalancer");
        this.configFile = configDirectory.resolve("config.yml");
    }

    void start() {
        ensureDefaultConfig();
        applySettings(loadSettings());
        WildlifeMod.LOGGER.info("WildAnimalBalancer running.");
    }

    void tick() {
        FabricBalancer current = balancer;
        if (current != null) current.tick(server);
    }

    void requestReload(Consumer<String> feedback) {
        long generation = reloadGeneration.incrementAndGet();
        Thread.ofVirtual().name("wildlife-config-reload").start(() -> {
            try {
                Settings settings = loadSettings();
                server.execute(() -> {
                    if (stopped || generation != reloadGeneration.get()) return;
                    applySettings(settings);
                    feedback.accept("WildAnimalBalancer config reloaded.");
                });
            } catch (RuntimeException exception) {
                WildlifeMod.LOGGER.error("Could not reload WildAnimalBalancer config", exception);
                server.execute(() -> feedback.accept(
                        "WildAnimalBalancer config reload failed; the previous settings remain active."));
            }
        });
    }

    void stop() {
        stopped = true;
        reloadGeneration.incrementAndGet();
        balancer = null;
        if (metrics != null) {
            metrics.stop();
            metrics = null;
        }
        if (spawnLogger != null) {
            spawnLogger.close();
            spawnLogger = null;
        }
    }

    List<String> statusLines() {
        return BalancerStats.statusLines(stats.snapshot(), currentGauges());
    }

    private void applySettings(Settings settings) {
        FabricBalancer previous = balancer;
        balancer = null;
        if (metrics != null) {
            metrics.stop();
            metrics = null;
        }
        SpawnLogger oldLogger = spawnLogger;
        spawnLogger = null;
        if (oldLogger != null) {
            Thread.ofVirtual().name("wildlife-spawn-log-close").start(oldLogger::close);
        }

        if (settings.spawnLogFile()) {
            spawnLogger = new SpawnLogger(
                    configDirectory.resolve("spawn-log.jsonl"),
                    (message, error) -> {
                        if (error == null) WildlifeMod.LOGGER.warn(message);
                        else WildlifeMod.LOGGER.warn(message, error);
                    });
        }
        FabricBalancer next = new FabricBalancer(
                settings, stats, spawnLogger == null ? null : spawnLogger::write);
        balancer = next;
        if (settings.metricsEnabled()) startMetrics(settings);
        if (previous != null) WildlifeMod.LOGGER.info("WildAnimalBalancer runtime replaced.");
    }

    private void startMetrics(Settings settings) {
        try {
            metrics = MetricsServer.start(
                    settings.metricsBind(), settings.metricsPort(), this::renderPrometheus);
            WildlifeMod.LOGGER.info("Prometheus metrics at http://{}:{}/metrics",
                    settings.metricsBind(), settings.metricsPort());
        } catch (IOException exception) {
            WildlifeMod.LOGGER.warn("Could not start metrics endpoint on {}:{}; continuing without metrics.",
                    settings.metricsBind(), settings.metricsPort(), exception);
        }
    }

    private String renderPrometheus() {
        return BalancerStats.prometheus(stats.snapshot(), currentGauges());
    }

    private BalancerStats.Gauges currentGauges() {
        FabricBalancer current = balancer;
        return current == null ? new BalancerStats.Gauges(0, 0, 0) : current.gauges();
    }

    private void ensureDefaultConfig() {
        try {
            Files.createDirectories(configDirectory);
            if (Files.exists(configFile)) return;
            Path bundledConfig = FabricLoader.getInstance()
                    .getModContainer(WildlifeMod.MOD_ID)
                    .flatMap(container -> container.findPath("config.yml"))
                    .orElseThrow(() -> new IOException("bundled config.yml is missing"));
            Files.copy(bundledConfig, configFile);
        } catch (IOException exception) {
            WildlifeMod.LOGGER.warn("Could not write default config {}; documented defaults will be used.",
                    configFile, exception);
        }
    }

    private Settings loadSettings() {
        Consumer<String> warn = WildlifeMod.LOGGER::warn;
        Map<String, List<String>> vanilla;
        try (InputStream input = WildlifeConfig.class.getResourceAsStream(
                "/" + WildlifeConfig.VANILLA_BIOME_RESOURCE)) {
            vanilla = WildlifeConfig.loadBiomeResource(
                    input, WildlifeRuntime::resolveSpecies, warn);
        } catch (IOException | RuntimeException exception) {
            WildlifeMod.LOGGER.warn("Could not read bundled {}; vanilla biome filtering disabled.",
                    WildlifeConfig.VANILLA_BIOME_RESOURCE, exception);
            vanilla = Map.of();
        }

        YamlSection config;
        try (InputStream input = Files.newInputStream(configFile)) {
            config = YamlSection.load(input);
        } catch (IOException | RuntimeException exception) {
            WildlifeMod.LOGGER.warn("Could not read {}; using documented defaults.", configFile, exception);
            config = YamlSection.from(Map.of());
        }
        Settings settings = WildlifeConfig.parseSettings(
                config, vanilla, WildlifeRuntime::resolveSpecies, warn);
        for (String world : settings.enabledWorlds()) {
            if (!world.contains(":")) {
                WildlifeMod.LOGGER.warn("enabled-worlds entry '{}' is not a dimension id; use values like minecraft:overworld", world);
            }
        }
        return settings;
    }

    private static Optional<String> resolveSpecies(String id) {
        Identifier identifier = Identifier.tryParse(id);
        if (identifier == null) return Optional.empty();
        return BuiltInRegistries.ENTITY_TYPE.getOptional(identifier)
                .map(type -> BuiltInRegistries.ENTITY_TYPE.getKey(type).toString());
    }
}
