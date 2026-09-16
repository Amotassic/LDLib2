package com.lowdragmc.lowdraglib2.compat.network.chat;

import com.lowdragmc.lowdraglib2.compat.network.codec.StreamCodec;
import com.mojang.serialization.Codec;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

public final class ComponentSerialization {
    public static final Codec<Component> CODEC = Codec.STRING.xmap(Component.Serializer::fromJson, Component.Serializer::toJson);
    public static final StreamCodec<FriendlyByteBuf, Component> STREAM_CODEC =
            StreamCodec.of(FriendlyByteBuf::writeComponent, FriendlyByteBuf::readComponent);

    private ComponentSerialization() {
    }
}
