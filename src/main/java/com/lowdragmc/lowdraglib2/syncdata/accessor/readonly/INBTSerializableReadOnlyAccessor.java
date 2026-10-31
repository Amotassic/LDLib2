package com.lowdragmc.lowdraglib2.syncdata.accessor.readonly;

import com.lowdragmc.lowdraglib2.Platform;
import com.lowdragmc.lowdraglib2.core.mixins.accessor.DelegatingOpsAccessor;
import com.lowdragmc.lowdraglib2.syncdata.accessor.IMarkFunction;
import com.lowdragmc.lowdraglib2.syncdata.mark.MarkStrategies;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JavaOps;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.RegistryOps;
import net.neoforged.neoforge.common.CommonHooks;
import net.neoforged.neoforge.common.util.INBTSerializable;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Compares values with the {@link MarkStrategies strategy} registered for their class, or else with what
 * {@code serializeNBT} writes. Subclasses may save or sync more than the base class, so they keep comparing what
 * {@link #readReadOnlyValue} returns.
 */
@SuppressWarnings("unchecked")
public class INBTSerializableReadOnlyAccessor implements IReadOnlyAccessor<INBTSerializable<?>>, IMarkFunction<INBTSerializable<?>, Object> {

    private static final IMarkFunction<INBTSerializable<?>, Object> MARKS = MarkStrategies.orElse(new SavedTagMark());

    private record SavedTagMark() implements IMarkFunction<INBTSerializable<?>, Tag> {
        @Override
        public @NotNull Tag obtainManagedMark(@NotNull INBTSerializable<?> value) {
            return value.serializeNBT(Platform.getFrozenRegistry());
        }

        @Override
        public boolean areDifferent(@NotNull Tag managedMark, @NotNull INBTSerializable<?> value) {
            return !value.serializeNBT(Platform.getFrozenRegistry()).equals(managedMark);
        }

        @Override
        public Tag updateMark(@NotNull Tag managedMark, @NotNull INBTSerializable<?> value) {
            var tag = value.serializeNBT(Platform.getFrozenRegistry());
            return tag.equals(managedMark) ? null : tag;
        }
    }

    private boolean subclassed() {
        return getClass() != INBTSerializableReadOnlyAccessor.class;
    }

    private Object readValue(INBTSerializable<?> value) {
        return readReadOnlyValue(Platform.getFrozenRegistry().createSerializationContext(JavaOps.INSTANCE), value);
    }

    @Override
    public boolean test(Class<?> type) {
        return INBTSerializable.class.isAssignableFrom(type);
    }

    @Override
    public @NotNull Object obtainManagedMark(@NotNull INBTSerializable<?> value) {
        return subclassed() ? readValue(value) : MARKS.obtainManagedMark(value);
    }

    @Override
    public boolean areDifferent(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        return subclassed() ? !Objects.equals(managedMark, readValue(value)) : MARKS.areDifferent(managedMark, value);
    }

    @Override
    public Object updateMark(@NotNull Object managedMark, @NotNull INBTSerializable<?> value) {
        // a subclass may override the two methods above, which the default goes through
        return subclassed() ? IMarkFunction.super.updateMark(managedMark, value) : MARKS.updateMark(managedMark, value);
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
