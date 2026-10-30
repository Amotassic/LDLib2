package com.lowdragmc.lowdraglib2.test.syncdata.bench;

import com.lowdragmc.lowdraglib2.CommonProxy;
import com.lowdragmc.lowdraglib2.misc.FluidStorage;
import com.lowdragmc.lowdraglib2.misc.ItemStackTransfer;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntity;
import com.lowdragmc.lowdraglib2.syncdata.annotation.DescSynced;
import com.lowdragmc.lowdraglib2.syncdata.annotation.Persisted;
import com.lowdragmc.lowdraglib2.syncdata.holder.blockentity.ISyncPersistRPCBlockEntity;
import com.lowdragmc.lowdraglib2.syncdata.storage.FieldManagedStorage;
import com.lowdragmc.lowdraglib2.test.syncdata.SyncProbeHolder;
import io.netty.buffer.Unpooled;
import lombok.Getter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.network.connection.ConnectionType;

import java.util.BitSet;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The {@link SyncBenchProfiles#MIXED} field mix as a real block entity, with counters for the work the sync framework
 * causes on the server thread.
 */
public class SyncBenchBlockEntity extends BlockEntity implements ISyncPersistRPCBlockEntity {
    private static final int SIZE_SAMPLE_EVERY = 16;

    public static final AtomicLong SET_CHANGED_CALLS = new AtomicLong();
    public static final AtomicLong SET_CHANGED_NANOS = new AtomicLong();
    public static final AtomicLong PACKETS = new AtomicLong();
    public static final AtomicLong SAMPLED_PACKETS = new AtomicLong();
    public static final AtomicLong SAMPLED_PACKET_BYTES = new AtomicLong();

    @Getter
    private final FieldManagedStorage syncStorage = new FieldManagedStorage(this);
    public volatile boolean syncEnabled = true;
    public int setChangedCalls;

    @Persisted
    @DescSynced
    public boolean working;
    @Persisted
    @DescSynced
    public boolean active;
    @Persisted
    @DescSynced
    public boolean allowOutput = true;
    @Persisted
    @DescSynced
    public int tier = 1;
    @Persisted
    @DescSynced
    public long energy = 100_000;
    @Persisted
    @DescSynced
    public Direction facing = Direction.NORTH;
    @Persisted
    @DescSynced
    public Direction outputFacing = Direction.SOUTH;
    @Persisted
    @DescSynced
    public SyncBenchProfiles.Mode mode = SyncBenchProfiles.Mode.AUTO;
    @Persisted
    @DescSynced
    public final ItemStackTransfer importItems = new ItemStackTransfer(9);
    @Persisted
    @DescSynced
    public final ItemStackTransfer exportItems = new ItemStackTransfer(9);
    @Persisted
    @DescSynced
    public final FluidStorage tank = new FluidStorage(16000);
    @Persisted
    @DescSynced
    public final SyncProbeHolder.Part logic = new SyncProbeHolder.Part(this::notifyPersistence);

    public SyncBenchBlockEntity(BlockPos pos, BlockState state) {
        super(CommonProxy.SYNC_BENCH_BE_TYPE.get(), pos, state);
        for (int i = 0; i < importItems.getSlots(); i++) {
            importItems.setStackInSlot(i, new ItemStack(Items.IRON_INGOT, 16 + i));
        }
        tank.fill(new FluidStack(Fluids.WATER, 8000), IFluidHandler.FluidAction.EXECUTE);
    }

    public void work(long tick) {
        logic.progress = (int) (tick % logic.duration);
        working = true;
        energy -= 32;
        if (tick % 20 == 0) {
            exportItems.setStackInSlot(0, new ItemStack(Items.IRON_INGOT, (int) (tick / 20 % 64) + 1));
        }
    }

    @Override
    public boolean isAsyncValid() {
        return syncEnabled && ISyncPersistRPCBlockEntity.super.isAsyncValid();
    }

    @Override
    public void setChanged() {
        long start = System.nanoTime();
        super.setChanged();
        SET_CHANGED_NANOS.addAndGet(System.nanoTime() - start);
        SET_CHANGED_CALLS.incrementAndGet();
        setChangedCalls++;
    }

    @Override
    public SPacketAutoSyncBlockEntity createSyncPacket(BitSet changed, byte[] data, CompoundTag extra) {
        var packet = ISyncPersistRPCBlockEntity.super.createSyncPacket(changed, data, extra);
        if (PACKETS.incrementAndGet() % SIZE_SAMPLE_EVERY == 0 && level != null) {
            var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess(), ConnectionType.NEOFORGE);
            try {
                SPacketAutoSyncBlockEntity.CODEC.encode(buf, packet);
                SAMPLED_PACKET_BYTES.addAndGet(buf.readableBytes());
                SAMPLED_PACKETS.incrementAndGet();
            } finally {
                buf.release();
            }
        }
        return packet;
    }
}
