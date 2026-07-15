package com.ouroboros.wildlife.fabric;

import com.mojang.brigadier.CommandDispatcher;
import me.lucko.fabric.api.permissions.v0.Permissions;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.PermissionLevel;

/** Brigadier registration for Fabric's wildlife admin command. */
final class WildlifeCommands {
    private WildlifeCommands() {}

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("wildlife")
                .requires(source -> Permissions.check(
                        source, "wildlife.admin", PermissionLevel.GAMEMASTERS))
                .then(Commands.literal("reload").executes(context -> {
                    WildlifeRuntime runtime = WildlifeMod.runtime();
                    if (runtime == null) {
                        context.getSource().sendFailure(Component.literal(
                                "WildAnimalBalancer is not running."));
                        return 0;
                    }
                    context.getSource().sendSystemMessage(Component.literal(
                            "WildAnimalBalancer config reload requested."));
                    runtime.requestReload(message -> context.getSource().sendSystemMessage(
                            Component.literal(message)));
                    return 1;
                }))
                .then(Commands.literal("status").executes(context -> {
                    WildlifeRuntime runtime = WildlifeMod.runtime();
                    if (runtime == null) {
                        context.getSource().sendFailure(Component.literal(
                                "WildAnimalBalancer is not running."));
                        return 0;
                    }
                    for (String line : runtime.statusLines()) {
                        context.getSource().sendSystemMessage(Component.literal(line));
                    }
                    return 1;
                })));
    }
}
