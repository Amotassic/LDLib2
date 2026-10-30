package com.lowdragmc.lowdraglib2.syncdata.accessor.readonly;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.core.mixins.accessor.DelegatingOpsAccessor;
import com.lowdragmc.lowdraglib2.misc.FluidStorage;
import com.lowdragmc.lowdraglib2.syncdata.accessor.IMarkFunction;
import com.lowdragmc.lowdraglib2.syncdata.utils.StackSnapshots;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JavaOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.INBTSerializable;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

@SuppressWarnings("unchecked")
public class INBTSerializableReadOnlyAccessor implements IReadOnlyAccessor<INBTSerializable<?>>, IMarkFunction<INBTSerializable<?>, Object> {

    /**
     * How a value is compared with its last synced state. Comparing slots against copies is the same as comparing what
     * {@code serializeNBT} writes, as long as the class still saves and reads its slots the way the base class does.
     */
    private enum Snapshot {
        ITEMS, FLUID, SAVED_TAG, READ_VALUE
    }

    private record FluidMark(FluidStack fluid, int capacity) {}

    private static final ClassValue<Snapshot> SNAPSHOTS = new ClassValue<>() {
        @Override
        protected Snapshot computeValue(Class<?> type) {
            if (ItemStackHandler.class.isAssignableFrom(type)
                    && declaredBy(type, ItemStackHandler.class, "serializeNBT", HolderLookup.Provider.class)
                    && declaredBy(type, ItemStackHandler.class, "getSlots")
                    && declaredBy(type, ItemStackHandler.class, "getStackInSlot", int.class)) {
                return Snapshot.ITEMS;
            }
            if (FluidStorage.class.isAssignableFrom(type)
                    && declaredBy(type, FluidStorage.class, "serializeNBT", HolderLookup.Provider.class)
                    && declaredBy(type, FluidTank.class, "getFluid")
                    && declaredBy(type, FluidTank.class, "getCapacity")) {
                return Snapshot.FLUID;
            }
            return Snapshot.SAVED_TAG;
        }
    };

    private static boolean declaredBy(Class<?> type, Class<?> owner, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters).getDeclaringClass() == owner;
        } catch (NoSuchMethodException | LinkageError e) {
            // looking a method up resolves every public signature of the class, which fails on a dedicated server
            // when one mentions a client class
            return false;
        }
    }

    @Override
    public boolean test(Class<?> type) {
        return INBTSerializable.class.isAssignableFrom(type);
    }

    /**
     * Subclasses may save or sync more than the base class does, so they keep comparing what
     * {@link #readReadOnlyValue} returns, as before this accessor had marks of its own.
     */
    private Snapshot snapshotOf(INBTSerializable<?> value) {
        return getClass() == INBTSerializableReadOnlyAccessor.class ? SNAPSHOTS.get(value.getClass()) : Snapshot.READ_VALUE;
    }

    private Object readValue(INBTSerializable<?> value) {
        return readReadOnlyValue(Platform.getFrozenRegistry().createSerializationContext(JavaOps.INSTANCE), value);
    }

    @Override
    public @NotNull Object obtainManagedMark(@NotNull INBTSerializable<?> value) {
        return switch (snapshotOf(value)) {
            case ITEMS -> {
                var handler = (ItemStackHandler) value;
                var stacks = new ItemStack[handler.getSlots()];
                for (int i = 0; i < stacks.length; i++) {
                    stacks[i] = StackSnapshots.of(handler.getStackInSlot(i));
                }
                yield stacks;
            }
            case FLUID -> {
                var tank = (FluidStorage) value;
                yield new FluidMark(StackSnapshots.of(tank.getFluid()), tank.getCapacity());
            }
            case SAVED_TAG -> value.serializeNBT(Platform.getFrozenRegistry());
            case READ_VALUE -> readValue(value);
        };
    }

    @Override
    public boolean areDifferent(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        return switch (snapshotOf(value)) {
            case ITEMS -> {
                if (!(managedMark instanceof ItemStack[] stacks)) yield true;
                var handler = (ItemStackHandler) value;
                if (stacks.length != handler.getSlots()) yield true;
                for (int i = 0; i < stacks.length; i++) {
                    if (!ItemStack.matches(stacks[i], handler.getStackInSlot(i))) yield true;
                }
                yield false;
            }
            case FLUID -> {
                if (!(managedMark instanceof FluidMark mark)) yield true;
                var tank = (FluidStorage) value;
                yield mark.capacity != tank.getCapacity() || !FluidStack.matches(mark.fluid, tank.getFluid());
            }
            case SAVED_TAG -> !value.serializeNBT(Platform.getFrozenRegistry()).equals(managedMark);
            case READ_VALUE -> !Objects.equals(managedMark, readValue(value));
        };
    }

    @Override
    public Object updateMark(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        if (snapshotOf(value) != Snapshot.SAVED_TAG) {
            return IMarkFunction.super.updateMark(managedMark, value);
        }
        var tag = value.serializeNBT(Platform.getFrozenRegistry());
        return tag.equals(managedMark) ? null : tag;
    }

    @Override
    public <T> T readReadOnlyValue(DynamicOps<T> op, @NotNull INBTSerializable<?> value) {
        HolderLookup.Provider registry = Platform.getFrozenRegistry();
        if (op instanceof RegistryOps<T> registryOps) {
            registry = CommonHooks.extractLookupProvider(registryOps);
        }
        var tag = value.serializeNBT(registry);
        return (op == NbtOps.INSTANCE || op instanceof DelegatingOpsAccessor<?> accessor && accessor.getDelegate() == NbtOps.INSTANCE) ? (T) tag : NbtOps.INSTANCE.convertTo(op, tag);
    }

    @Override
    public <T> void writeReadOnlyValue(DynamicOps<T> op, INBTSerializable<?> value, T payload) {
        HolderLookup.Provider registry = Platform.getFrozenRegistry();
        if (op instanceof RegistryOps<T> registryOps) {
            registry = CommonHooks.extractLookupProvider(registryOps);
        }
        ((INBTSerializable)value).deserializeNBT(registry,
                (op == NbtOps.INSTANCE || op instanceof DelegatingOpsAccessor<?> accessor && accessor.getDelegate() == NbtOps.INSTANCE) ?
                (Tag) payload : op.convertTo(NbtOps.INSTANCE, payload));
    }

    @Override
    public void readReadOnlyValueToStream(RegistryFriendlyByteBuf buffer, @NotNull INBTSerializable<?> value) {
        buffer.writeNbt(value.serializeNBT(buffer.registryAccess()));
    }

    @Override
    public void writeReadOnlyValueFromStream(RegistryFriendlyByteBuf buffer, @NotNull INBTSerializable<?> value) {
        var nbt = buffer.readNbt(NbtAccounter.unlimitedHeap());
        if (nbt != null) {
            ((INBTSerializable)value).deserializeNBT(buffer.registryAccess(), nbt);
        }
    }

}
