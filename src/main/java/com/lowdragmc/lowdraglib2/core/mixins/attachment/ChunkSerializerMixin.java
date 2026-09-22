package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import com.llamalad7.mixinextras.sugar.Local;
import com.lowdragmc.lowdraglib2.LDLib2;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static net.neoforged.neoforge.attachment.AttachmentHolder.ATTACHMENTS_NBT_KEY;

@Mixin(ChunkSerializer.class)
public class ChunkSerializerMixin {

    @Inject(method = "read", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/chunk/ChunkAccess;setLightCorrect(Z)V"))
    private static void read(ServerLevel level, PoiManager poiManager, ChunkPos pos, CompoundTag tag, CallbackInfoReturnable<ProtoChunk> cir, @Local ChunkAccess chunkaccess) {
        if (tag.contains(ATTACHMENTS_NBT_KEY, net.minecraft.nbt.Tag.TAG_COMPOUND))
            chunkaccess.getAttachmentHolder().deserializeAttachments(level.registryAccess(), tag.getCompound(ATTACHMENTS_NBT_KEY));
    }

    @Inject(method = "write", at = @At(value = "RETURN"))
    private static void write(ServerLevel level, ChunkAccess chunk, CallbackInfoReturnable<CompoundTag> cir, @Local(ordinal = 0) CompoundTag compoundtag) {
        try {
            final CompoundTag capTag = chunk.getAttachmentHolder().serializeAttachments(level.registryAccess());
            if (capTag != null) compoundtag.put(ATTACHMENTS_NBT_KEY, capTag);
        } catch (Exception exception) {
            LDLib2.LOGGER.error("Failed to write chunk attachments. An attachment has likely thrown an exception trying to write state. It will not persist. Report this to the mod author", exception);
        }
    }
}
