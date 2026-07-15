package com.ouroboros.wildlife.fabric;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Versioned server-to-client sample consumed by the optional admin HUD. */
public record BalancerHudPayload(
        int wild,
        int target,
        int streak,
        int budgetLeft,
        int cycleSeconds
) implements CustomPacketPayload {
    public static final Type<BalancerHudPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("wildanimalbalancer", "hud/v1"));
    public static final StreamCodec<RegistryFriendlyByteBuf, BalancerHudPayload> CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, BalancerHudPayload::wild,
                    ByteBufCodecs.VAR_INT, BalancerHudPayload::target,
                    ByteBufCodecs.VAR_INT, BalancerHudPayload::streak,
                    ByteBufCodecs.VAR_INT, BalancerHudPayload::budgetLeft,
                    ByteBufCodecs.VAR_INT, BalancerHudPayload::cycleSeconds,
                    BalancerHudPayload::new);

    @Override
    public Type<BalancerHudPayload> type() {
        return TYPE;
    }
}
