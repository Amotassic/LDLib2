package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import com.lowdragmc.lowdraglib2.Platform;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import static net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY;

@Mixin(BlockEntity.class)
public abstract class BlockEntityMixin implements IAttachmentHolder {
    @Shadow
    public abstract void setChanged();

    @Unique
    private final AttachmentHolder ldlib2$attachmentHolder = new AttachmentHolder(this);

    @Override
    public AttachmentHolder getAttachmentHolder() {
        return ldlib2$attachmentHolder;
    }

    @Override
    public @Nullable <T> T setData(AttachmentType<T> type, T data) {
        setChanged();
        return getAttachmentHolder().setData(type, data);
    }

    @Override
    public @Nullable <T> T removeData(AttachmentType<T> type) {
        setChanged();
        return getAttachmentHolder().removeData(type);
    }

    @Inject(method = "saveAdditional", at = @At(value = "TAIL"))
    public void saveWithoutId(CompoundTag tag, CallbackInfo ci) {
        var attachments = getAttachmentHolder().serializeAttachments(Platform.getFrozenRegistry());
        if (attachments != null) tag.put(ATTACHMENTS_NBT_KEY, attachments);
    }

    @Inject(method = "load", at = @At(value = "TAIL"))
    public void load(CompoundTag tag, CallbackInfo ci) {
        if (tag.contains(ATTACHMENTS_NBT_KEY, Tag.TAG_COMPOUND))
            getAttachmentHolder().deserializeAttachments(Platform.getFrozenRegistry(), tag.getCompound(ATTACHMENTS_NBT_KEY));
    }
}
