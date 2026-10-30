package com.lowdragmc.lowdraglib2.syncdata.utils;

import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.PatchedDataComponentMap;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

/**
 * Copies of stacks to compare their later state with. A plain copy shares its component map with the original until one
 * of them writes, so a copy taken off the server thread can keep sharing a map the original goes on changing. These copies
 * own their map right away.
 */
public final class StackSnapshots {
    private StackSnapshots() {}

    public static ItemStack of(ItemStack stack) {
        var copy = stack.copy();
        if (copy.getComponents() instanceof PatchedDataComponentMap components) {
            components.applyPatch(DataComponentPatch.EMPTY);
        }
        return copy;
    }

    public static FluidStack of(FluidStack stack) {
        var copy = stack.copy();
        if (!copy.isEmpty()) {
            copy.getComponents().applyPatch(DataComponentPatch.EMPTY);
        }
        return copy;
    }
}
