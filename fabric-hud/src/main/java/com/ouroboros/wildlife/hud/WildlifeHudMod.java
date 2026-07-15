package com.ouroboros.wildlife.hud;

import com.mojang.blaze3d.platform.InputConstants;
import com.ouroboros.wildlife.fabric.BalancerHudPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.KeyMapping;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/** Optional client entry point for the admin wildlife HUD. */
public final class WildlifeHudMod implements ClientModInitializer {
    private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(
            "wildanimalbalancer", "admin_hud");

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(
                BalancerHudPayload.TYPE,
                (payload, context) -> WildlifeHudOverlay.update(payload));

        KeyMapping toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.wildanimalbalancer.toggle_hud",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_H,
                KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggle.consumeClick()) WildlifeHudOverlay.toggle();
        });

        HudElementRegistry.attachElementAfter(
                VanillaHudElements.BOSS_BAR,
                HUD_ID,
                WildlifeHudOverlay::extractRenderState);
    }
}
