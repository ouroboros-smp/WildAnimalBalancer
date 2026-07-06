package com.ouroboros.wildlife;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Main plugin entry point.
 *
 * onEnable writes config.yml on first run (saveDefaultConfig), builds the
 * balancer from those values, and starts it. /wildlife reload re-reads the
 * file and restarts the balancer, so you can tune spawn targets live without
 * a server restart.
 *
 * Monitoring: stats counters live here (not in the balancer) so they survive
 * /wildlife reload and lifetime counters stay monotonic. /wildlife status
 * prints them; the optional spawn log and Prometheus endpoint are started and
 * stopped alongside the balancer.
 */
public final class WildlifePlugin extends JavaPlugin {

    private final BalancerStats stats = new BalancerStats();
    // volatile: the metrics thread reads it while a reload swaps it.
    private volatile WildAnimalBalancer balancer;
    private SpawnLogger spawnLogger;
    private MetricsServer metrics;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // copies the bundled config.yml into the plugin folder if absent
        startBalancer();
        getLogger().info("WildAnimalBalancer running.");
    }

    @Override
    public void onDisable() {
        stopRuntime();
    }

    private void startBalancer() {
        stopRuntime();
        WildAnimalBalancer.Settings cfg = loadSettings();
        if (cfg.spawnLogFile()) {
            spawnLogger = new SpawnLogger(getDataFolder().toPath().resolve("spawn-log.jsonl"), getLogger());
        }
        balancer = new WildAnimalBalancer(this, cfg, stats,
                spawnLogger == null ? null : spawnLogger::write);
        balancer.start();
        if (cfg.metricsEnabled()) {
            try {
                metrics = MetricsServer.start(cfg.metricsBind(), cfg.metricsPort(), this::renderPrometheus);
                getLogger().info("Prometheus metrics at http://" + cfg.metricsBind() + ":" + cfg.metricsPort() + "/metrics");
            } catch (IOException ex) {
                getLogger().warning("Could not start metrics endpoint on " + cfg.metricsBind() + ":"
                        + cfg.metricsPort() + " (" + ex.getMessage() + "); continuing without metrics.");
            }
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

    /** Scrape body for the metrics endpoint. Reads counters and map sizes only. */
    private String renderPrometheus() {
        return BalancerStats.prometheus(stats.snapshot(), currentGauges());
    }

    private BalancerStats.Gauges currentGauges() {
        WildAnimalBalancer b = balancer;
        return b == null ? new BalancerStats.Gauges(0, 0, 0) : b.gauges();
    }

    private WildAnimalBalancer.Settings loadSettings() {
        Consumer<String> warn = msg -> getLogger().warning(msg);
        Map<String, List<EntityType>> vanilla;
        try (InputStream in = getResource(VANILLA_BIOME_RESOURCE)) {
            vanilla = loadBiomeResource(in, warn);
        } catch (IOException ex) {
            getLogger().warning("Could not read bundled " + VANILLA_BIOME_RESOURCE + "; vanilla biome filtering disabled.");
            vanilla = Map.of();
        }
        return parseSettings(getConfig(), vanilla, warn);
    }

    /** Bundled snapshot of vanilla per-biome passive animal spawns (see that file's header). */
    static final String VANILLA_BIOME_RESOURCE = "vanilla-biome-animals.yml";

    /**
     * Build Settings from a configuration. Pure aside from the warning callback
     * (which receives complete, operator-facing messages), so it can be unit
     * tested against the bundled config.yml without a live server.
     * vanillaBiomeAnimals is the parsed bundled snapshot (see loadBiomeResource).
     *
     * Misconfigurations are clamped HERE, with a warning, so Settings always
     * reports the true effective values: cycle-seconds below 1 becomes 1, and
     * min-spawn-distance is capped at scan-radius (a spawn ring outside the
     * census box would never be counted and would respawn every eligible cycle).
     */
    static WildAnimalBalancer.Settings parseSettings(FileConfiguration c,
                                                     Map<String, List<EntityType>> vanillaBiomeAnimals,
                                                     Consumer<String> warn) {
        List<EntityType> animals = new ArrayList<>();
        for (String name : c.getStringList("animals")) {
            try {
                animals.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                warn.accept("Unknown animal type in config, skipping: " + name);
            }
        }
        if (animals.isEmpty()) {
            animals.add(EntityType.COW);
            animals.add(EntityType.PIG);
            animals.add(EntityType.SHEEP);
            animals.add(EntityType.CHICKEN);
        }

        // Per-biome overrides: keys normalised to lowercase biome key paths, values
        // replace the global list in that biome. An empty list disables the biome.
        Map<String, List<EntityType>> biomeAnimals =
                parseBiomeMap(c.getConfigurationSection("biome-animals"), warn);

        Set<String> worlds = new HashSet<>(c.getStringList("enabled-worlds"));

        long cycleSeconds = c.getLong("cycle-seconds", 30);
        if (cycleSeconds < 1) {
            warn.accept("cycle-seconds " + cycleSeconds + " is below 1, clamping to 1");
            cycleSeconds = 1;
        }

        int scanRadius = c.getInt("scan-radius", 96);
        int minSpawnDist = c.getInt("min-spawn-distance", 24);
        if (minSpawnDist > scanRadius) {
            warn.accept("min-spawn-distance " + minSpawnDist + " exceeds scan-radius " + scanRadius
                    + ", clamping to " + scanRadius);
            minSpawnDist = scanRadius;
        }

        int statusLogCycles = c.getInt("status-log-cycles", 0);
        if (statusLogCycles < 0) {
            warn.accept("status-log-cycles " + statusLogCycles + " is negative, disabling the periodic summary");
            statusLogCycles = 0;
        }

        boolean metricsEnabled = c.getBoolean("metrics.enabled", false);
        String metricsBind = c.getString("metrics.bind", "127.0.0.1");
        int metricsPort = c.getInt("metrics.port", 9940);
        if (metricsEnabled && (metricsPort < 1 || metricsPort > 65535)) {
            warn.accept("metrics.port " + metricsPort + " is not a valid port, disabling the metrics endpoint");
            metricsEnabled = false;
        }
        if (metricsEnabled && isWildcardBind(metricsBind)) {
            warn.accept("metrics.bind " + metricsBind + " listens on ALL interfaces; bind the metrics"
                    + " endpoint to localhost or a private scrape network, not the open internet");
        }

        return new WildAnimalBalancer.Settings(
                cycleSeconds,
                scanRadius,
                c.getInt("base-target", 8),
                c.getInt("per-additional-player", 4),
                c.getInt("max-target", 40),
                c.getInt("max-per-cycle", 6),
                minSpawnDist,
                c.getInt("spawn-tries", 20),
                c.getInt("min-sky-light", 7),
                c.getInt("deficit-cycles", 3),
                c.getInt("cell-hourly-budget", 30),
                c.getBoolean("persistent-spawns", true),
                animals,
                biomeAnimals,
                c.getBoolean("vanilla-biome-defaults", true),
                vanillaBiomeAnimals,
                worlds,
                c.getBoolean("log-spawns", false),
                c.getBoolean("spawn-log-file", false),
                statusLogCycles,
                metricsEnabled,
                metricsBind,
                metricsPort
        );
    }

    /** Binds that expose the endpoint on every interface. Static for unit testing. */
    static boolean isWildcardBind(String bind) {
        if (bind == null) return true;
        String b = bind.trim();
        return b.isEmpty() || b.equals("0.0.0.0") || b.equals("::") || b.equals("[::]") || b.equals("*");
    }

    /**
     * Parse a section of biome-key -> species-list entries. Keys are normalised
     * to lowercase, unknown species are reported and skipped, a null section
     * yields an empty map. Shared by the config's biome-animals section and the
     * bundled vanilla snapshot.
     */
    static Map<String, List<EntityType>> parseBiomeMap(ConfigurationSection section, Consumer<String> warn) {
        Map<String, List<EntityType>> map = new HashMap<>();
        if (section == null) return map;
        for (String biome : section.getKeys(false)) {
            List<EntityType> list = new ArrayList<>();
            for (String name : section.getStringList(biome)) {
                try {
                    list.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
                } catch (IllegalArgumentException ex) {
                    warn.accept("Unknown animal type in config, skipping: " + name);
                }
            }
            map.put(biome.toLowerCase(Locale.ROOT), list);
        }
        return map;
    }

    /** Parse the bundled vanilla biome snapshot from a resource stream. */
    static Map<String, List<EntityType>> loadBiomeResource(InputStream in, Consumer<String> warn) {
        if (in == null) return Map.of();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        return parseBiomeMap(yaml, warn);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("wildlife.admin")) {
            sender.sendMessage("You don't have permission to do that.");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();
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
