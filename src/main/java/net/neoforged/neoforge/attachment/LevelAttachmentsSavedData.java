/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.attachment;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

@ApiStatus.Internal
public class LevelAttachmentsSavedData extends SavedData {
    private static final String NAME = "neoforge_data_attachments";

    public static void init(ServerLevel level) {
        // Querying the attachment a single time is enough to initialize it,
        // and make sure it gets saved when the level is saved.
        level.getDataStorage().computeIfAbsent(tag -> new LevelAttachmentsSavedData(level, tag), () -> new LevelAttachmentsSavedData(level), NAME);
    }

    private final ServerLevel level;

    public LevelAttachmentsSavedData(ServerLevel level) {
        this.level = level;
    }

    public LevelAttachmentsSavedData(ServerLevel level, CompoundTag tag) {
        this.level = level;
        AttachmentHolder.get(level).deserializeAttachments(level.registryAccess(), tag);
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        // Make sure we don't return null
        return Objects.requireNonNullElseGet(AttachmentHolder.get(level).serializeAttachments(level.registryAccess()), CompoundTag::new);
    }

    @Override
    public boolean isDirty() {
        // Always re-save
        return true;
    }
}
