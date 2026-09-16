/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.attachment;

import com.mojang.logging.LogUtils;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLLoader;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.MustBeInvokedByOverriders;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Implementation class for objects that can hold data attachments.
 * For the user-facing methods, see {@link IAttachmentHolder}.
 */
@SuppressWarnings("unchecked")
public class AttachmentHolder implements IAttachmentHolder {
    public static final String ATTACHMENTS_NBT_KEY = "neoforge:attachments";
    private static final boolean IN_DEV = !FMLLoader.isProduction();
    private static final Logger LOGGER = LogUtils.getLogger();
    private final IAttachmentHolder exposedHolder;

    public AttachmentHolder(IAttachmentHolder holder) {
        this.exposedHolder = holder;
    }

    /**
     * Get the attachment holder for the given object.
     * @param o can only be {@link net.minecraft.world.entity.Entity}, {@link net.minecraft.world.level.block.entity.BlockEntity}, {@link net.minecraft.world.level.chunk.ChunkAccess}, {@link net.minecraft.world.level.Level}.
     * @return the attachment holder for the given object.
     */
    public static AttachmentHolder get(Object o) {return ((IAttachmentHolder) o).getAttachmentHolder();}

    private void validateAttachmentType(AttachmentType<?> type) {
        Objects.requireNonNull(type);
        if (!IN_DEV) return;

        if (!NeoForgeRegistries.ATTACHMENT_TYPES.containsValue(type)) {
            throw new IllegalArgumentException("Data attachment type with default value " + type.defaultValueSupplier.apply(getExposedHolder()) + " must be registered!");
        }
    }

    @Nullable
    Map<AttachmentType<?>, Object> attachments;

    /**
     * Create the attachment map if it does not yet exist, or return the current map.
     */
    final Map<AttachmentType<?>, Object> getAttachmentMap() {
        if (attachments == null) {
            attachments = new IdentityHashMap<>(4);
        }
        return attachments;
    }

    /**
     * Returns the attachment holder that is exposed to the user.
     */
    IAttachmentHolder getExposedHolder() {
        return exposedHolder;
    }

    @Override
    public final boolean hasAttachments() {
        return attachments != null && !attachments.isEmpty();
    }

    @Override
    public final boolean hasData(AttachmentType<?> type) {
        validateAttachmentType(type);
        return attachments != null && attachments.containsKey(type);
    }

    @Override
    public final <T> T getData(AttachmentType<T> type) {
        validateAttachmentType(type);
        T ret = (T) getAttachmentMap().get(type);
        if (ret == null) {
            ret = type.defaultValueSupplier.apply(getExposedHolder());
            attachments.put(type, ret);
            syncData(type);
        }
        return ret;
    }

    @Override
    @Nullable
    public <T> T getExistingDataOrNull(AttachmentType<T> type) {
        validateAttachmentType(type);
        if (attachments == null) {
            return null;
        }
        return (T) this.attachments.get(type);
    }

    @Override
    @MustBeInvokedByOverriders
    public <T> @Nullable T setData(AttachmentType<T> type, T data) {
        validateAttachmentType(type);
        Objects.requireNonNull(data);
        var previousData = (T) getAttachmentMap().put(type, data);
        syncData(type);
        return previousData;
    }

    @Override
    @MustBeInvokedByOverriders
    public <T> @Nullable T removeData(AttachmentType<T> type) {
        validateAttachmentType(type);
        if (attachments == null) {
            return null;
        }
        var previousData = (T) attachments.remove(type);
        syncData(type);
        return previousData;
    }

    @Override
    public AttachmentHolder getAttachmentHolder() {return this;}

    /**
     * Writes the serializable attachments to a tag.
     * Returns {@code null} if there are no serializable attachments.
     */
    @Nullable
    public final CompoundTag serializeAttachments(HolderLookup.Provider provider) {
        if (attachments == null) {
            return null;
        }
        CompoundTag tag = null;
        for (var entry : attachments.entrySet()) {
            var type = entry.getKey();
            var key = NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type);
            if (type.serializer != null) {
                try {
                    Tag serialized = ((IAttachmentSerializer<?, Object>) type.serializer).write(entry.getValue(), provider);
                    if (serialized != null) {
                        if (tag == null)
                            tag = new CompoundTag();
                        tag.put(key.toString(), serialized);
                    }
                } catch (Exception exception) {
                    LOGGER.error("Failed to serialize data attachment {}. Skipping.", key, exception);
                }
            }
        }
        return tag;
    }

    /**
     * Reads serializable attachments from a tag previously created via {@link #serializeAttachments(HolderLookup.Provider)}.
     *
     * <p>This does not trigger {@link IAttachmentHolder#syncData syncing} of the deserialized attachments.
     */
    public final void deserializeAttachments(HolderLookup.Provider provider, CompoundTag tag) {
        for (var key : tag.getAllKeys()) {
            // Use tryParse to not discard valid attachment type keys, even if there is a malformed key.
            ResourceLocation keyLocation = ResourceLocation.tryParse(key);
            if (keyLocation == null) {
                LOGGER.error("Encountered invalid data attachment key {}. Skipping.", key);
                continue;
            }

            var type = NeoForgeRegistries.ATTACHMENT_TYPES.getValue(keyLocation);
            if (type == null || type.serializer == null) {
                LOGGER.error("Encountered unknown or non-serializable data attachment {}. Skipping.", key);
                continue;
            }

            try {
                getAttachmentMap().put(type, ((IAttachmentSerializer<Tag, ?>) type.serializer).read(getExposedHolder(), tag.get(key), provider));
            } catch (Exception exception) {
                LOGGER.error("Failed to deserialize data attachment {}. Skipping.", key, exception);
            }
        }
    }
}
