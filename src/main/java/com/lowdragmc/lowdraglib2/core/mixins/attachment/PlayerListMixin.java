package com.lowdragmc.lowdraglib2.core.mixins.attachment;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.neoforged.neoforge.attachment.AttachmentSync;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public class PlayerListMixin {

    @Inject(method = "sendLevelInfo", at = @At(value = "TAIL"))
    public void sendLevelInfo(ServerPlayer player, ServerLevel level, CallbackInfo ci) {
        AttachmentSync.syncInitialLevelAttachments(level, player);
    }
}
