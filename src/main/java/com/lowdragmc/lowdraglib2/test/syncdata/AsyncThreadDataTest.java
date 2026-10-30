package com.lowdragmc.lowdraglib2.test.syncdata;

import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.async.AsyncThreadData;
import com.lowdragmc.lowdraglib2.async.IAsyncLogic;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

@GameTestHolder(LDLib2.MOD_ID)
public class AsyncThreadDataTest {
    private static final long PERIODS_NANOS = 250_000_000L;
    private static final long GIVE_UP_NANOS = 5_000_000_000L;
    private static final long TICK_NANOS = 2_000_000L;

    private static final class CountingLogic implements IAsyncLogic {
        private final List<Long> periods = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void asyncTick(long periodID) {
            periods.add(periodID);
        }
    }

    @GameTest(template = "empty", batch = "ldlib2_async_thread", timeoutTicks = 6000)
    @PrefixGameTestTemplate(false)
    public static void logicAddedTwiceRunsOncePerPeriodUntilRemoved(GameTestHelper helper) {
        var data = AsyncThreadData.getOrCreate(helper.getLevel());
        var logic = new CountingLogic();
        data.addAsyncLogic(logic);
        data.addAsyncLogic(logic);
        var step = new int[1];
        var since = new long[]{System.nanoTime()};
        var ranBeforeRemoval = new int[1];
        helper.onEachTick(() -> {
            long now = System.nanoTime();
            if (step[0] == 0 && (logic.periods.size() >= 6 || now - since[0] >= GIVE_UP_NANOS)) {
                List<Long> periods;
                synchronized (logic.periods) {
                    periods = List.copyOf(logic.periods);
                }
                data.removeAsyncLogic(logic);
                if (periods.size() < 6) {
                    helper.fail("the logic ran " + periods.size() + " times in 5 seconds");
                }
                if (new HashSet<>(periods).size() != periods.size()) {
                    helper.fail("a logic added twice ran twice in a period: " + periods);
                }
                ranBeforeRemoval[0] = logic.periods.size();
                step[0] = 1;
                since[0] = now;
            } else if (step[0] == 1 && now - since[0] >= PERIODS_NANOS) {
                // a period already running when it was removed may still run it once
                if (logic.periods.size() > ranBeforeRemoval[0] + 1) {
                    helper.fail("the logic kept running after it was removed once: " + logic.periods.size() + " runs, "
                            + ranBeforeRemoval[0] + " before");
                }
                helper.succeed();
                return;
            }
            long wait;
            while ((wait = now + TICK_NANOS - System.nanoTime()) > 0) {
                LockSupport.parkNanos(wait);
            }
        });
    }
}
