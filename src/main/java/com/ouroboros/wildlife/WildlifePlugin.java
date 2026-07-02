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
 */
public final class WildlifePlugin extends JavaPlugin {

    private WildAnimalBalancer balancer;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // copies the bundled config.yml into the plugin folder if absent
        startBalancer();
        getLogger().info("WildAnimalBalancer running.");
    }

    @Override
    public void onDisable() {
        if (balancer != null) balancer.stop();
    }

    private void startBalancer() {
        if (balancer != null) balancer.stop();
        balancer = new WildAnimalBalancer(this, loadSettings());
        balancer.start();
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
                worlds
        );
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
        sender.sendMessage("Usage: /" + label + " reload");
        return true;
    }
}
