package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import net.minecraft.world.level.Level;
import net.neoforged.neoforge.attachment.AttachmentHolder;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(Level.class)
public class LevelMixin implements IAttachmentHolder {
    @Unique
    private final AttachmentHolder ldlib2$attachmentHolder = new AttachmentHolder(this);

    @Override
    public AttachmentHolder getAttachmentHolder() {
        return ldlib2$attachmentHolder;
    }
}
