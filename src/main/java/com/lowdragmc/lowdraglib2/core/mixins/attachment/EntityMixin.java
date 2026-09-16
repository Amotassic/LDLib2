package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY;

@Mixin(Entity.class)
public abstract class EntityMixin implements IAttachmentHolder {
    @Shadow
    public abstract Level level();

    @Unique
    private final AttachmentHolder ldlib2$attachmentHolder = new AttachmentHolder(this);

    @Override
    public AttachmentHolder getAttachmentHolder() {
        return ldlib2$attachmentHolder;
    }

    @Inject(method = "saveWithoutId", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;addAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    public void saveWithoutId(CompoundTag compound, CallbackInfoReturnable<CompoundTag> cir) {
        var attachments = getAttachmentHolder().serializeAttachments(level().registryAccess());
        if (attachments != null) compound.put(ATTACHMENTS_NBT_KEY, attachments);
    }

    @Inject(method = "load", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;readAdditionalSaveData(Lnet/minecraft/nbt/CompoundTag;)V"))
    public void load(CompoundTag compound, CallbackInfo ci) {
        if (compound.contains(ATTACHMENTS_NBT_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND))
            getAttachmentHolder().deserializeAttachments(level().registryAccess(), compound.getCompound(ATTACHMENTS_NBT_KEY));
    }
}
