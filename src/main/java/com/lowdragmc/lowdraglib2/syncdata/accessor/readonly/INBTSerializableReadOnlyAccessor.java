package com.lowdragmc.lowdraglib2.syncdata.accessor.readonly;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.core.mixins.accessor.DelegatingOpsAccessor;
import com.lowdragmc.lowdraglib2.misc.FluidStorage;
import com.lowdragmc.lowdraglib2.syncdata.accessor.IMarkFunction;
import com.mojang.serialization.DynamicOps;
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

@SuppressWarnings("unchecked")
public class INBTSerializableReadOnlyAccessor implements IReadOnlyAccessor<INBTSerializable<?>>, IMarkFunction<INBTSerializable<?>, Object> {

    /**
     * How a value is compared with its last synced state. Comparing slots against copies is the same as comparing what
     * {@code serializeNBT} writes, as long as the class still saves and reads its slots the way the base class does.
     */
    private enum Snapshot {
        ITEMS, FLUID, SAVED_TAG
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
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Override
    public boolean test(Class<?> type) {
        return INBTSerializable.class.isAssignableFrom(type);
    }

    @Override
    public @NotNull Object obtainManagedMark(@NotNull INBTSerializable<?> value) {
        return switch (SNAPSHOTS.get(value.getClass())) {
            case ITEMS -> {
                var handler = (ItemStackHandler) value;
                var stacks = new ItemStack[handler.getSlots()];
                for (int i = 0; i < stacks.length; i++) {
                    stacks[i] = handler.getStackInSlot(i).copy();
                }
                yield stacks;
            }
            case FLUID -> {
                var tank = (FluidStorage) value;
                yield new FluidMark(tank.getFluid().copy(), tank.getCapacity());
            }
            case SAVED_TAG -> value.serializeNBT(Platform.getFrozenRegistry());
        };
    }

    @Override
    public boolean areDifferent(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        return switch (SNAPSHOTS.get(value.getClass())) {
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
        };
    }

    @Override
    public Object updateMark(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        if (SNAPSHOTS.get(value.getClass()) != Snapshot.SAVED_TAG) {
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
