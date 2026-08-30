package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import javax.annotation.Nullable;

@Mixin(ChunkAccess.class)
public abstract class ChunkAccessMixin implements IAttachmentHolder {
    @Shadow
    public abstract void setUnsaved(boolean unsaved);
    @Unique
    private final AttachmentHolder ldlib2$attachmentHolder = new AttachmentHolder(this);

    @Override
    public AttachmentHolder getAttachmentHolder() {
        return ldlib2$attachmentHolder;
    }

    @Nullable
    public <T> T setData(AttachmentType<T> type, T data) {
        setUnsaved(true);
        return getAttachmentHolder().setData(type, data);
    }

    @Nullable
    public <T> T removeData(AttachmentType<T> type) {
        setUnsaved(true);
        return getAttachmentHolder().removeData(type);
    }
}
