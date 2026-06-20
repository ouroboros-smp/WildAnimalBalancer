package com.ouroboros.wildlife;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
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
        return parseSettings(getConfig(),
                name -> getLogger().warning("Unknown animal type in config, skipping: " + name));
    }

    /**
     * Build Settings from a configuration. Pure aside from the warning callback,
     * so it can be unit tested against the bundled config.yml without a live server.
     */
    static WildAnimalBalancer.Settings parseSettings(FileConfiguration c, Consumer<String> onUnknownAnimal) {
        List<EntityType> animals = new ArrayList<>();
        for (String name : c.getStringList("animals")) {
            try {
                animals.add(EntityType.valueOf(name.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                onUnknownAnimal.accept(name);
            }
        }
        if (animals.isEmpty()) {
            animals.add(EntityType.COW);
            animals.add(EntityType.PIG);
            animals.add(EntityType.SHEEP);
            animals.add(EntityType.CHICKEN);
        }

        Set<String> worlds = new HashSet<>(c.getStringList("enabled-worlds"));

        return new WildAnimalBalancer.Settings(
                c.getLong("cycle-seconds", 30),
                c.getInt("scan-radius", 96),
                c.getInt("base-target", 8),
                c.getInt("per-additional-player", 4),
                c.getInt("max-target", 40),
                c.getInt("max-per-cycle", 6),
                c.getInt("min-spawn-distance", 24),
                c.getInt("spawn-tries", 20),
                c.getInt("min-sky-light", 7),
                animals,
                worlds
        );
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
