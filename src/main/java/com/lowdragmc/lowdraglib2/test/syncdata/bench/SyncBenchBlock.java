package com.lowdragmc.lowdraglib2.test.syncdata.bench;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

public class SyncBenchBlock extends Block implements EntityBlock {
    public static final SyncBenchBlock BLOCK = new SyncBenchBlock();

    private SyncBenchBlock() {
        super(Properties.of().destroyTime(2));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SyncBenchBlockEntity(pos, state);
    }
}
