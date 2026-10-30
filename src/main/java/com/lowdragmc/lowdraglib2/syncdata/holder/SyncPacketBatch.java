package com.lowdragmc.lowdraglib2.syncdata.holder;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.async.AsyncThreadData;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntities;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntity;
import it.unimi.dsi.fastutil.longs.Long2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.common.util.FakePlayer;
import org.jetbrains.annotations.ApiStatus;

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

    private final Map<ServerLevel, Long2ObjectMap<List<Update>>> updates = new LinkedHashMap<>();

    /**
     * Queues the changes a sync found to go out with the rest of the current async period's.
     *
     * @return {@code false} if the calling thread is not running an async period, so the caller has to send them
     */
    public static boolean offer(ISyncMangedHolder holder, BitSet changed, byte[] data) {
        var chunk = holder.getTrackingPos();
        if (chunk == null) return false;
        var batch = AsyncThreadData.periodTask(SyncPacketBatch.class, SyncPacketBatch::new);
        if (batch == null) return false;
        batch.updates.computeIfAbsent(holder.getServerLevel(), level -> new Long2ObjectLinkedOpenHashMap<>())
                .computeIfAbsent(chunk.toLong(), key -> new ArrayList<>())
                .add(new Update(holder, changed, data));
        return true;
    }

    @Override
    public void run(MinecraftServer server) {
        updates.forEach((level, chunks) -> {
            for (var chunk : chunks.long2ObjectEntrySet()) {
                var receivers = new ArrayList<Receiver>();
                for (var player : level.getChunkSource().chunkMap.getPlayers(new ChunkPos(chunk.getLongKey()), false)) {
                    // a fake player's connection has no channel to check or send through
                    if (!(player instanceof FakePlayer)) {
                        receivers.add(new PlayerReceiver(player));
                    }
                }
                if (!receivers.isEmpty()) {
                    send(receivers, build(level, chunk.getValue()));
                }
            }
        });
    }

    @ApiStatus.Internal
    public record Update(ISyncMangedHolder holder, BitSet changed, byte[] data) {}

    /**
     * The packets one chunk's updates make: their entries for the players who take
     * {@link SPacketAutoSyncBlockEntities}, the same updates as single packets for the rest, and the custom payloads of
     * holders that build their own.
     */
    @ApiStatus.Internal
    public record ChunkPackets(List<SPacketAutoSyncBlockEntities.Entry> entries, List<ClientboundCustomPayloadPacket> singles,
                               List<ClientboundCustomPayloadPacket> custom) {}

    @ApiStatus.Internal
    public static ChunkPackets build(ServerLevel level, List<Update> updates) {
        var packets = new ChunkPackets(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (var update : updates) {
            if (update.holder instanceof BlockEntity blockEntity && blockEntity.isRemoved()) continue;
            try {
                var extra = new CompoundTag();
                update.holder.writeCustomSyncData(level.registryAccess(), extra);
                var payload = update.holder.createSyncPacket(update.changed, update.data, extra);
                if (payload.getClass() == SPacketAutoSyncBlockEntity.class) {
                    packets.entries.add(SPacketAutoSyncBlockEntities.Entry.of((SPacketAutoSyncBlockEntity) payload));
                    packets.singles.add(new ClientboundCustomPayloadPacket(payload));
                } else {
                    packets.custom.add(new ClientboundCustomPayloadPacket(payload));
                }
            } catch (Throwable e) {
                LDLib2.LOGGER.error("Failed to build the sync packet of {}", update.holder, e);
            }
        }
        return packets;
    }

    /**
     * Where {@link #send} delivers a chunk's packets: a watching player's connection.
     */
    @ApiStatus.Internal
    public interface Receiver {
        boolean hasChannel(CustomPacketPayload.Type<?> type);

        void send(Packet<?> packet);
    }

    private record PlayerReceiver(ServerPlayer player) implements Receiver {
        @Override
        public boolean hasChannel(CustomPacketPayload.Type<?> type) {
            return player.connection.hasChannel(type);
        }

        @Override
        public void send(Packet<?> packet) {
            player.connection.send(packet);
        }
    }

    @ApiStatus.Internal
    public static void send(List<? extends Receiver> receivers, ChunkPackets packets) {
        List<ClientboundCustomPayloadPacket> batched = null;
        for (var receiver : receivers) {
            try {
                if (!packets.entries.isEmpty() && receiver.hasChannel(SPacketAutoSyncBlockEntities.TYPE)) {
                    if (batched == null) {
                        batched = SPacketAutoSyncBlockEntities.pack(packets.entries, MAX_PACKET_BYTES).stream()
                                .map(ClientboundCustomPayloadPacket::new).toList();
                    }
                    batched.forEach(receiver::send);
                } else {
                    sendSupported(receiver, packets.singles);
                }
                sendSupported(receiver, packets.custom);
            } catch (Throwable e) {
                LDLib2.LOGGER.error("Failed to send sync packets to {}", receiver, e);
            }
        }
    }

    private static void sendSupported(Receiver receiver, List<ClientboundCustomPayloadPacket> packets) {
        for (var packet : packets) {
            if (receiver.hasChannel(packet.payload().type())) {
                receiver.send(packet);
            }
        }
    }
}
