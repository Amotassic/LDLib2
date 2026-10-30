package com.lowdragmc.lowdraglib2.async;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.Platform;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import lombok.Getter;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.Set;
import java.util.concurrent.*;

/**
 * @author KilaBash
 * @date 2022/9/7
 * @implNote AsyncWorldSavedData
 * used for Async logic, it's world-related.
 * all logic runnable {@link IAsyncLogic} will be constantly executed in a async thread per tick.
 * warning, you have to add and remove runnable manually.
 */
@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
public class AsyncThreadData extends SavedData {
    private static final String THREAD_NAME_FORMAT = "LDLib Async Thread-%d";
    private static final int DEFAULT_SCHEDULE_PERIOD_MS = 50;

    public final ServerLevel serverLevel;

    public static AsyncThreadData getOrCreate(ServerLevel serverLevel) {
        return serverLevel.getDataStorage().computeIfAbsent(new SavedData.Factory<>(() -> new AsyncThreadData(serverLevel), (tag, provider) -> new AsyncThreadData(serverLevel, tag)), LDLib2.MOD_ID);
    }

    private AsyncThreadData(ServerLevel serverLevel) {
        this.serverLevel = serverLevel;
    }

    private AsyncThreadData(ServerLevel serverLevel, CompoundTag compoundTag) {
        this(serverLevel);
    }

    @Override
    public CompoundTag save(CompoundTag compoundTag, HolderLookup.Provider provider) {
        return compoundTag;
    }


    // ********************************* async thread ********************************* //
    private final CopyOnWriteArrayList<IAsyncLogic> asyncLogics = new CopyOnWriteArrayList<>();
    private ScheduledExecutorService executorService;
    private final static ThreadFactory THREAD_FACTORY = new ThreadFactoryBuilder()
            .setNameFormat(THREAD_NAME_FORMAT)
            .setDaemon(true)
            .build();
    private static final ThreadLocal<Set<BlockEntity>> PERIOD_CHANGES = new ThreadLocal<>();
    @Getter
    private long periodID = Long.MIN_VALUE;

    public void createExecutorService() {
        if (executorService != null && !executorService.isShutdown()) return;
        executorService = Executors.newSingleThreadScheduledExecutor(THREAD_FACTORY);
        executorService.scheduleAtFixedRate(this::searchingTask, 0, DEFAULT_SCHEDULE_PERIOD_MS, TimeUnit.MILLISECONDS);
    }

    /**
     * add a async logic runnable
     * @param logic runnable
     */
    public void addAsyncLogic(IAsyncLogic logic) {
        asyncLogics.add(logic);
        createExecutorService();
    }

    /**
     * remove logic runnable
     * @param logic runnable
     */
    public void removeAsyncLogic(IAsyncLogic logic) {
        asyncLogics.remove(logic);
        if (asyncLogics.isEmpty()) {
            releaseExecutorService();
        }
    }

    private void searchingTask() {
        var changed = new ReferenceOpenHashSet<BlockEntity>();
        try {
            if (Platform.isServerNotSafe()) return;
            PERIOD_CHANGES.set(changed);
            for (IAsyncLogic logic : asyncLogics) {
                try {
                    logic.asyncTick(periodID);
                } catch (Throwable e) {
                    LDLib2.LOGGER.error("asyncThreadLogic error with an async logic {}", logic, e);
                }
            }
        } finally {
            PERIOD_CHANGES.remove();
            flushChangedBlockEntities(changed);
        }
        periodID++;
    }

    public static boolean isThreadService() {
        return PERIOD_CHANGES.get() != null;
    }

    /**
     * Calls {@link BlockEntity#setChanged()} once on the server thread at the end of the current async period, however
     * many times the block entity asks during the period. Use it instead of scheduling {@code setChanged} directly from
     * {@link IAsyncLogic#asyncTick}.
     *
     * @return {@code false} if the calling thread is not running an async period; nothing is scheduled then, and the
     * caller has to call {@code setChanged} itself
     */
    public static boolean deferSetChanged(BlockEntity blockEntity) {
        var changed = PERIOD_CHANGES.get();
        if (changed == null) return false;
        changed.add(blockEntity);
        return true;
    }

    private void flushChangedBlockEntities(Set<BlockEntity> changed) {
        if (changed.isEmpty()) return;
        var server = serverLevel.getServer();
        if (!Platform.serverSafe(server)) return;
        try {
            server.executeIfPossible(() -> {
                if (!Platform.serverSafe(server)) return;
                for (var blockEntity : changed) {
                    try {
                        blockEntity.setChanged();
                    } catch (Throwable e) {
                        LDLib2.LOGGER.error("Failed to mark block entity {} changed", blockEntity, e);
                    }
                }
            });
        } catch (RejectedExecutionException ignored) {
            // The server can begin shutting down between the safety check and task submission.
        }
    }

    public void releaseExecutorService() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        executorService = null;
    }

}
