/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.attachment;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.compat.network.ConnectionType;
import com.lowdragmc.lowdraglib2.compat.network.RegistryFriendlyByteBuf;
import com.lowdragmc.lowdraglib2.networking.LDLNetworking;
import com.lowdragmc.lowdraglib2.utils.ByteBufUtil;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.neoforged.neoforge.network.payload.SyncAttachmentsPayload;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

@ApiStatus.Internal
@Mod.EventBusSubscriber(modid = LDLib2.MOD_ID)
public final class AttachmentSync {

    private static SyncAttachmentsPayload.Target syncTarget(AttachmentHolder holder) {
        var target = holder.getExposedHolder();
        if (target instanceof BlockEntity blockEntity)
            return new SyncAttachmentsPayload.BlockEntityTarget(blockEntity.getBlockPos());
        if (target instanceof LevelChunk chunk) return new SyncAttachmentsPayload.ChunkTarget(chunk.getPos());
        if (target instanceof Entity entity) return new SyncAttachmentsPayload.EntityTarget(entity.getId());
        if (target instanceof Level) return new SyncAttachmentsPayload.LevelTarget();
        throw new UnsupportedOperationException("Attachment holder class is not supported: " + target);
    }

    /**
     * Syncs the update (possibly removal) of a single attachment type to a list of players.
     */
    private static <T> void syncUpdate(IAttachmentHolder o, AttachmentType<T> type, List<ServerPlayer> players) {
        if (type.syncHandler == null) return;
        var holder = o.getAttachmentHolder();

        RegistryAccess registryAccess = null;
        for (var player : players) {
            if (type.syncHandler.sendToPlayer(holder.getExposedHolder(), player)) {
                registryAccess = player.level().registryAccess();
                break;
            }
        }
        // This also serves as a short-circuit if there are no players to sync data to.
        if (registryAccess == null) {
            return;
        }
        var data = ByteBufUtil.writeCustomData(buf -> {
            var existingData = holder.getExistingDataOrNull(type);
            if (existingData != null) {
                buf.writeBoolean(true);
                type.syncHandler.write(buf, holder.getData(type), false);
            } else {
                buf.writeBoolean(false);
            }
        }, registryAccess);
        var packet = new SyncAttachmentsPayload(syncTarget(holder), List.of(type), data);
        for (var player : players) {
            if (type.syncHandler.sendToPlayer(holder.getExposedHolder(), player)) {
                LDLNetworking.sendToPlayer(player, packet);
            }
        }
    }

    public static void syncBlockEntityUpdate(BlockEntity blockEntity, AttachmentType<?> type) {
        if (type.syncHandler == null || !(blockEntity.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        syncUpdate(blockEntity, type, serverLevel.getChunkSource().chunkMap.getPlayers(new ChunkPos(blockEntity.getBlockPos()), false));
    }

    public static void syncChunkUpdate(LevelChunk chunk, AttachmentType<?> type) {
        if (type.syncHandler == null || !(chunk.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        syncUpdate(chunk, type, serverLevel.getChunkSource().chunkMap.getPlayers(chunk.getPos(), false));
    }

    public static void syncEntityUpdate(Entity entity, AttachmentType<?> type) {
        if (type.syncHandler == null || !(entity.level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var chunkMap = serverLevel.getChunkSource().chunkMap;
        ArrayList<ServerPlayer> players = new ArrayList<>();
        ChunkMap.TrackedEntity trackedEntity = chunkMap.entityMap.get(entity.getId());
        if (trackedEntity != null) {
            for(ServerPlayerConnection connection : trackedEntity.seenBy) {
                players.add(connection.getPlayer());
            }
        }
        if (entity instanceof ServerPlayer serverPlayer) {
            // Players do not track themselves
            players.add(serverPlayer);
        }
        syncUpdate(entity, type, players);
    }

    public static void syncLevelUpdate(ServerLevel level, AttachmentType<?> type) {
        if (type.syncHandler == null) {
            return;
        }
        syncUpdate(level, type, level.players());
    }

    /**
     * Constructs a payload to sync all syncable attachments to a player, if any.
     */
    @Nullable
    private static SyncAttachmentsPayload syncInitialAttachments(IAttachmentHolder o, ServerPlayer to) {
        var holder = o.getAttachmentHolder();
        if (holder.attachments == null) {
            return null;
        }
        boolean anySyncableAttachment = false;
        for (var attachment : holder.attachments.keySet()) {
            anySyncableAttachment = anySyncableAttachment | attachment.syncHandler != null;
        }
        if (!anySyncableAttachment) {
            return null;
        }
        List<AttachmentType<?>> syncedTypes = new ArrayList<>();
        var data = ByteBufUtil.writeCustomData(buf -> {
            for (var entry : holder.attachments.entrySet()) {
                AttachmentType<?> type = entry.getKey();
                @SuppressWarnings("unchecked")
                var syncHandler = (AttachmentSyncHandler<Object>) type.syncHandler;
                if (syncHandler != null) {
                    int indexBefore = buf.writerIndex();
                    buf.writeBoolean(true);
                    int indexBetween = buf.writerIndex();
                    syncHandler.write(buf, entry.getValue(), true);
                    if (indexBetween < buf.writerIndex()) {
                        // Actually wrote something
                        syncedTypes.add(type);
                    } else {
                        buf.writerIndex(indexBefore);
                    }
                }
            }
        }, to.level().registryAccess());
        return new SyncAttachmentsPayload(syncTarget(holder), syncedTypes, data);
    }

    /**
     * Handles initial syncing of block entity and chunk attachments.
     */
    @SubscribeEvent
    public static void onChunkSent(ChunkWatchEvent.Watch event) {
        var chunkPayload = syncInitialAttachments(event.getChunk(), event.getPlayer());
        if (chunkPayload != null) {
            LDLNetworking.sendToPlayer(event.getPlayer(), chunkPayload);
        }
        for (var blockEntity : event.getChunk().getBlockEntities().values()) {
            var blockEntityPayload = syncInitialAttachments(blockEntity, event.getPlayer());
            if (blockEntityPayload != null) {
                LDLNetworking.sendToPlayer(event.getPlayer(), blockEntityPayload);
            }
        }
    }

    @SubscribeEvent
    public static void onLevelLoad(LevelEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level) LevelAttachmentsSavedData.init(level);
    }

    @SubscribeEvent
    public static void onStartEntityTracking(PlayerEvent.StartTracking event) {
        syncInitialEntityAttachments(event.getTarget(), (ServerPlayer) event.getEntity());
    }

    @SubscribeEvent
    public static void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        syncInitialPlayerAttachments((ServerPlayer) event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        syncInitialPlayerAttachments((ServerPlayer) event.getEntity());
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        syncInitialPlayerAttachments((ServerPlayer) event.getEntity());
    }

    /**
     * Handles initial syncing of entity attachments, except for a player's own attachments.
     */
    public static void syncInitialEntityAttachments(Entity entity, ServerPlayer to) {
        var packet = syncInitialAttachments(entity, to);
        if (packet != null) LDLNetworking.sendToPlayer(to, packet);
    }

    /**
     * Handles initial syncing of a player's own attachments.
     */
    public static void syncInitialPlayerAttachments(ServerPlayer player) {
        var packet = syncInitialAttachments(player, player);
        if (packet != null) LDLNetworking.sendToPlayer(player, packet);
    }

    /**
     * Handles initial syncing of level attachments. Needs to be called for login, respawn and teleports.
     */
    public static void syncInitialLevelAttachments(ServerLevel level, ServerPlayer to) {
        var packet = syncInitialAttachments(level, to);
        if (packet != null) LDLNetworking.sendToPlayer(to, packet);
    }

    public static void receiveSyncedDataAttachments(IAttachmentHolder o, RegistryAccess registryAccess, List<AttachmentType<?>> types, byte[] bytes) {
        var holder = o.getAttachmentHolder();
        var buf = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(bytes), registryAccess, ConnectionType.NEOFORGE);
        try {
            for (var type : types) {
                @SuppressWarnings("unchecked")
                var syncHandler = (AttachmentSyncHandler<Object>) type.syncHandler;
                if (syncHandler == null) {
                    throw new IllegalArgumentException("Received synced attachment type without a sync handler registered: " + NeoForgeRegistries.ATTACHMENT_TYPES.getKey(type));
                }
                var previousValue = holder.attachments == null ? null : holder.attachments.get(type);
                boolean hasAttachment = buf.readBoolean();
                var result = hasAttachment ? syncHandler.read(holder.getExposedHolder(), buf, previousValue) : null;
                if (result == null) {
                    if (holder.attachments != null) {
                        holder.attachments.remove(type);
                    }
                } else {
                    holder.getAttachmentMap().put(type, result);
                }
            }
        } catch (Exception exception) {
            throw new RuntimeException("Encountered exception when reading synced data attachments: " + types, exception);
        } finally {
            buf.release();
        }
    }

    private AttachmentSync() {}
}
