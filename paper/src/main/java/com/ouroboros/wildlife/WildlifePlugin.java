package com.ouroboros.wildlife;

import com.ouroboros.wildlife.core.BalancerStats;
import com.ouroboros.wildlife.core.MetricsServer;
import com.ouroboros.wildlife.core.Settings;
import com.ouroboros.wildlife.core.SpawnLogger;
import com.ouroboros.wildlife.core.WildlifeConfig;
import com.ouroboros.wildlife.core.YamlSection;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Paper and Folia entry point for the wild-animal balancer. */
public final class WildlifePlugin extends JavaPlugin {
    private final BalancerStats stats = new BalancerStats();
    private volatile WildAnimalBalancer balancer;
    private SpawnLogger spawnLogger;
    private MetricsServer metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        startBalancer();
        getLogger().info("WildAnimalBalancer running.");
    }

    @Override
    public void onDisable() {
        stopRuntime();
    }

    private void startBalancer() {
        stopRuntime();
        Settings settings = loadSettings();
        if (settings.spawnLogFile()) {
            spawnLogger = new SpawnLogger(
                    getDataFolder().toPath().resolve("spawn-log.jsonl"),
                    (message, error) -> {
                        if (error == null) getLogger().warning(message);
                        else getLogger().log(Level.WARNING, message, error);
                    });
        }
        balancer = new WildAnimalBalancer(this, settings, stats,
                spawnLogger == null ? null : spawnLogger::write);
        balancer.start();
        if (settings.metricsEnabled()) startMetrics(settings);
    }

    private void startMetrics(Settings settings) {
        try {
            metrics = MetricsServer.start(
                    settings.metricsBind(), settings.metricsPort(), this::renderPrometheus);
            getLogger().info("Prometheus metrics at http://" + settings.metricsBind() + ":"
                    + settings.metricsPort() + "/metrics");
        } catch (IOException exception) {
            getLogger().warning("Could not start metrics endpoint on " + settings.metricsBind() + ":"
                    + settings.metricsPort() + " (" + exception.getMessage()
                    + "); continuing without metrics.");
        }
    }

    private void stopRuntime() {
        if (balancer != null) {
            balancer.stop();
            balancer = null;
        }
        if (metrics != null) {
            metrics.stop();
            metrics = null;
        }
        if (spawnLogger != null) {
            spawnLogger.close();
            spawnLogger = null;
        }
    }

    private String renderPrometheus() {
        return BalancerStats.prometheus(stats.snapshot(), currentGauges());
    }

    private BalancerStats.Gauges currentGauges() {
        WildAnimalBalancer current = balancer;
        return current == null ? new BalancerStats.Gauges(0, 0, 0) : current.gauges();
    }

    private Settings loadSettings() {
        Consumer<String> warn = message -> getLogger().warning(message);
        Map<String, List<String>> vanilla;
        try (InputStream input = getResource(WildlifeConfig.VANILLA_BIOME_RESOURCE)) {
            vanilla = WildlifeConfig.loadBiomeResource(input, WildlifePlugin::resolveSpecies, warn);
        } catch (IOException | RuntimeException exception) {
            getLogger().warning("Could not read bundled " + WildlifeConfig.VANILLA_BIOME_RESOURCE
                    + "; vanilla biome filtering disabled.");
            vanilla = Map.of();
        }

        Path configFile = getDataFolder().toPath().resolve("config.yml");
        YamlSection config;
        try (InputStream input = Files.newInputStream(configFile)) {
            config = YamlSection.load(input);
        } catch (IOException | RuntimeException exception) {
            getLogger().log(Level.WARNING, "Could not read " + configFile
                    + "; using documented defaults.", exception);
            config = YamlSection.from(Map.of());
        }
        return WildlifeConfig.parseSettings(config, vanilla, WildlifePlugin::resolveSpecies, warn);
    }

    private static Optional<String> resolveSpecies(String id) {
        NamespacedKey key = NamespacedKey.fromString(id);
        if (key == null) return Optional.empty();
        EntityType type = Registry.ENTITY_TYPE.get(key);
        return type == null ? Optional.empty() : Optional.of(type.getKey().asString());
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wildlife.admin")) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            startBalancer();
            sender.sendMessage("WildAnimalBalancer config reloaded.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
            for (String line : BalancerStats.statusLines(stats.snapshot(), currentGauges())) {
                sender.sendMessage(line);
            }
            return true;
        }
        sender.sendMessage("Usage: /" + label + " <reload|status>");
        return true;
    }
}
