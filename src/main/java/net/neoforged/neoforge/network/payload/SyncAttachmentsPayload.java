/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.network.payload;

import com.lowdragmc.lowdraglib2.compat.network.IPayloadContext;
import com.lowdragmc.lowdraglib2.compat.network.RegistryFriendlyByteBuf;
import com.lowdragmc.lowdraglib2.compat.network.custom.CustomPacketPayload;
import com.lowdragmc.lowdraglib2.utils.codec.StreamCodec;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.neoforged.neoforge.attachment.AttachmentSync;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.attachment.IAttachmentHolder;
import net.neoforged.neoforge.registries.NeoForgeRegistries;
import org.jetbrains.annotations.ApiStatus;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.List;

@ApiStatus.Internal
public record SyncAttachmentsPayload(Target target, List<AttachmentType<?>> types, byte[] syncPayload) implements CustomPacketPayload {
    public static final Type<SyncAttachmentsPayload> TYPE = new Type<>(new ResourceLocation(NeoForgeRegistries.MOD_ID, "sync_attachments"));;
    public static final StreamCodec<RegistryFriendlyByteBuf, SyncAttachmentsPayload> STREAM_CODEC =
            StreamCodec.of(SyncAttachmentsPayload::encode, SyncAttachmentsPayload::decode);
    public static final Logger LOGGER = LogUtils.getLogger();

    static void encode(RegistryFriendlyByteBuf buf, SyncAttachmentsPayload payload) {
        Target.encode(buf, payload.target);
        buf.writeInt(payload.types.size());
        for (var type : payload.types) {
            buf.writeInt(NeoForgeRegistries.ATTACHMENT_TYPES.getID(type));
        }
        buf.writeByteArray(payload.syncPayload);
    }

    static SyncAttachmentsPayload decode(RegistryFriendlyByteBuf buf) {
        Target decodedTarget = Target.decode(buf);
        int size = buf.readInt();
        var list = new ArrayList<AttachmentType<?>>(size);
        for (int i = 0; i < size; i++) {
            list.add(NeoForgeRegistries.ATTACHMENT_TYPES.getValue(buf.readInt()));
        }
        byte[] data = buf.readByteArray();
        return new SyncAttachmentsPayload(decodedTarget, list, data);
    }

    public static void execute(SyncAttachmentsPayload payload, IPayloadContext context) {
        Target t = payload.target;
        IAttachmentHolder o = null;
        var level = context.player().level();
        if (t instanceof BlockEntityTarget target) {
            var blockEntity = level.getBlockEntity(target.pos);
            if (blockEntity == null) {
                LOGGER.warn("Received synced attachments from unknown block entity");
            } else o = blockEntity;
        } else if (t instanceof ChunkTarget arget) {
            var pos = arget.pos;
            var chunk = level.getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk == null) {
                LOGGER.warn("Received synced attachments from unknown chunk");
            } else o = chunk;
        } else if (t instanceof EntityTarget target) {
            var entity = level.getEntity(target.entity);
            if (entity == null) {
                LOGGER.warn("Received synced attachments from unknown entity");
            } else o = entity;
        } else if (t instanceof LevelTarget) o = level;
        if (o != null) AttachmentSync.receiveSyncedDataAttachments(o, level.registryAccess(), payload.types, payload.syncPayload);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public sealed interface Target {

        static void encode(RegistryFriendlyByteBuf buf, Target target) {
            if (target instanceof BlockEntityTarget blockEntity) {
                buf.writeByte(0);
                buf.writeBlockPos(blockEntity.pos);
            } else if (target instanceof ChunkTarget chunk) {
                buf.writeByte(1);
                buf.writeChunkPos(chunk.pos);
            } else if (target instanceof EntityTarget entity) {
                buf.writeByte(2);
                buf.writeVarInt(entity.entity);
            } else if (target instanceof LevelTarget) buf.writeByte(3);
        }

        static Target decode(RegistryFriendlyByteBuf buf) {
            int type = buf.readByte();
            return switch (type) {
                case 0 -> new BlockEntityTarget(buf.readBlockPos());
                case 1 -> new ChunkTarget(buf.readChunkPos());
                case 2 -> new EntityTarget(buf.readVarInt());
                case 3 -> new LevelTarget();
                default -> throw new IllegalArgumentException("Unknown target type: " + type);
            };
        }
    }

    public record BlockEntityTarget(BlockPos pos) implements Target {}

    public record ChunkTarget(ChunkPos pos) implements Target {}

    public record EntityTarget(int entity) implements Target {}

    public static final class LevelTarget implements Target {}
}
