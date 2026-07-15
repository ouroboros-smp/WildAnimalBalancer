package com.ouroboros.wildlife.fabric;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Fabric entry point and one-time event registration. */
public final class WildlifeMod implements ModInitializer {
    public static final String MOD_ID = "wildanimalbalancer";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    private static volatile WildlifeRuntime runtime;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.clientboundPlay().register(
                BalancerHudPayload.TYPE, BalancerHudPayload.CODEC);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            WildlifeRuntime next = new WildlifeRuntime(server);
            runtime = next;
            next.start();
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            WildlifeRuntime current = runtime;
            runtime = null;
            if (current != null) current.stop();
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            WildlifeRuntime current = runtime;
            if (current != null) current.tick();
        });
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> WildlifeCommands.register(dispatcher));
    }

    static WildlifeRuntime runtime() {
        return runtime;
    }
}
