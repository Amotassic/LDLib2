/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.attachment;

import com.lowdragmc.lowdraglib2.LDLib2;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.event.entity.living.LivingConversionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.jetbrains.annotations.ApiStatus;

import java.util.function.Predicate;

@ApiStatus.Internal
@Mod.EventBusSubscriber(modid = LDLib2.MOD_ID)
public final class AttachmentInternals {
    /**
     * Copy some attachments to another holder.
     */
    private static void copyAttachments(HolderLookup.Provider provider, Object fromO, Object toO, Predicate<AttachmentType<?>> filter) {
        var from = AttachmentHolder.get(fromO);
        if (from.attachments == null) {
            return;
        }
        var to = AttachmentHolder.get(toO);
        for (var entry : from.attachments.entrySet()) {
            AttachmentType<?> type = entry.getKey();
            if (type.serializer == null) {
                continue;
            }
            @SuppressWarnings("unchecked")
            var copyHandler = (IAttachmentCopyHandler<Object>) type.copyHandler;
            if (filter.test(type)) {
                Object copy = copyHandler.copy(entry.getValue(), to.getExposedHolder(), provider);
                if (copy != null) {
                    to.getAttachmentMap().put(type, copy);
                }
            }
        }
    }

    public static void copyChunkAttachmentsOnPromotion(HolderLookup.Provider provider, Object from, Object to) {
        copyAttachments(provider, from, to, type -> true);
    }

    public static void copyEntityAttachments(Entity from, Entity to, boolean isDeath) {
        copyAttachments(from.level().registryAccess(), from, to, isDeath ? type -> type.copyOnDeath : type -> true);
    }

    @SubscribeEvent
    public static void onPlayerClone(PlayerEvent.Clone event) {
        copyEntityAttachments(event.getOriginal(), event.getEntity(), event.isWasDeath());
    }

    @SubscribeEvent
    public static void onLivingConvert(LivingConversionEvent.Post event) {
        copyEntityAttachments(event.getEntity(), event.getOutcome(), true);
    }

    private AttachmentInternals() {}
}
