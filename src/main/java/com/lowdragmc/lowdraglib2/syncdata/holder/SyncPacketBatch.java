package com.lowdragmc.lowdraglib2.syncdata.holder;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.async.AsyncThreadData;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntities;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The syncs an async period found, sent once it ends: the block entity updates of a chunk travel in one packet, and
 * nothing is built for chunks no player is watching.
 */
public final class SyncPacketBatch implements AsyncThreadData.PeriodTask {
    private static final int MAX_PACKET_BYTES = 256 * 1024;

    private record Update(ISyncMangedHolder holder, BitSet changed, byte[] data) {}

    private final Map<ServerLevel, Long2ObjectMap<List<Update>>> updates = new LinkedHashMap<>();

    /**
     * Queues the changes a sync found to go out with the rest of the current async period's.
     *
     * @return {@code false} if the calling thread is not running an async period, so the caller has to send them
     */
    public static boolean offer(ISyncMangedHolder holder, BitSet changed, byte[] data) {
        var batch = AsyncThreadData.periodTask(SyncPacketBatch.class, SyncPacketBatch::new);
        if (batch == null) return false;
        batch.updates.computeIfAbsent(holder.getServerLevel(), level -> new Long2ObjectLinkedOpenHashMap<>())
                .computeIfAbsent(holder.getTrackingPos().toLong(), chunk -> new ArrayList<>())
                .add(new Update(holder, changed, data));
        return true;
    }

    @Override
    public void run(MinecraftServer server) {
        updates.forEach((level, chunks) -> {
            for (var chunk : chunks.long2ObjectEntrySet()) {
                var players = level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunk.getLongKey()), false);
                if (!players.isEmpty()) {
                    send(level, players, chunk.getValue());
                }
            }
        });
    }

    private static void send(ServerLevel level, List<ServerPlayer> players, List<Update> updates) {
        var entries = new ArrayList<SPacketAutoSyncBlockEntities.Entry>();
        var singles = new ArrayList<ClientboundCustomPayloadPacket>();
        var others = new ArrayList<ClientboundCustomPayloadPacket>();
        for (var update : updates) {
            if (update.holder instanceof BlockEntity blockEntity && blockEntity.isRemoved()) continue;
            try {
                var extra = new CompoundTag();
                update.holder.writeCustomSyncData(level.registryAccess(), extra);
                var payload = update.holder.createSyncPacket(update.changed, update.data, extra);
                if (payload.getClass() == SPacketAutoSyncBlockEntity.class) {
                    entries.add(SPacketAutoSyncBlockEntities.Entry.of((SPacketAutoSyncBlockEntity) payload));
                    singles.add(new ClientboundCustomPayloadPacket(payload));
                } else {
                    others.add(new ClientboundCustomPayloadPacket(payload));
                }
            } catch (Throwable e) {
                LDLib2.LOGGER.error("Failed to build the sync packet of {}", update.holder, e);
            }
        }
        List<ClientboundCustomPayloadPacket> batched = null;
        for (var player : players) {
            var connection = player.connection;
            if (!entries.isEmpty() && connection.hasChannel(SPacketAutoSyncBlockEntities.TYPE)) {
                if (batched == null) {
                    batched = SPacketAutoSyncBlockEntities.pack(entries, MAX_PACKET_BYTES).stream()
                            .map(ClientboundCustomPayloadPacket::new).toList();
                }
                batched.forEach(connection::send);
            } else {
                sendSupported(player, singles);
            }
            sendSupported(player, others);
        }
    }

    private static void sendSupported(ServerPlayer player, List<ClientboundCustomPayloadPacket> packets) {
        for (var packet : packets) {
            if (player.connection.hasChannel(packet.payload())) {
                player.connection.send(packet);
            }
        }
    }
}
