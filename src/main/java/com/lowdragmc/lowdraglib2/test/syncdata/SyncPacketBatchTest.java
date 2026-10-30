package com.lowdragmc.lowdraglib2.test.syncdata;

import com.lowdragmc.lowdraglib2.CommonProxy;
import com.lowdragmc.lowdraglib2.LDLib2;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntities;
import com.lowdragmc.lowdraglib2.networking.s2c.SPacketAutoSyncBlockEntities.Entry;
import com.lowdragmc.lowdraglib2.utils.ByteBufUtil;
import com.lowdragmc.lowdraglib2.utils.TagBuilder;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Random;

@GameTestHolder(LDLib2.MOD_ID)
public class SyncPacketBatchTest {

    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    public static void batchedPacketRoundTrips(GameTestHelper helper) {
        var level = helper.getLevel();
        var bigData = new byte[10_000];
        new Random(42).nextBytes(bigData);
        var entries = List.of(
                new Entry(new BlockPos(1, 2, 3), CommonProxy.SYNC_TEST_BE_TYPE.get(), bits(0, 3), new byte[]{1, 2, 3}, new CompoundTag()),
                new Entry(new BlockPos(-4, 5, -6), CommonProxy.SYNC_BENCH_BE_TYPE.get(), new BitSet(), new byte[0],
                        TagBuilder.compound().add("a", 1).add("b", "text").add("c", TagBuilder.compound().add("d", 2.5).build()).build()),
                new Entry(new BlockPos(7, -64, 9), BlockEntityType.FURNACE, bits(70), bigData, new CompoundTag()));

        var decoded = SPacketAutoSyncBlockEntities.CODEC.decode(buffer(level, SPacketAutoSyncBlockEntities.CODEC, new SPacketAutoSyncBlockEntities(entries)));
        check(helper, decoded.entries().size() == entries.size(), "decoded " + decoded.entries().size() + " entries");
        for (int i = 0; i < entries.size(); i++) {
            var sent = entries.get(i);
            var got = decoded.entries().get(i);
            check(helper, sent.pos().equals(got.pos()) && sent.type() == got.type() && sent.changed().equals(got.changed())
                    && Arrays.equals(sent.data(), got.data()) && sent.extra().equals(got.extra()), "entry " + i + " changed on the way: " + got);
            int encoded = buffer(level, Entry.CODEC, sent).readableBytes();
            check(helper, encoded <= sent.sizeBound(), "entry " + i + " encodes to " + encoded + " bytes, bound " + sent.sizeBound());
        }
        helper.succeed();
    }

    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    public static void packingKeepsOrderAndSize(GameTestHelper helper) {
        var level = helper.getLevel();
        int maxBytes = 400;
        var entries = new ArrayList<Entry>();
        for (int size : new int[]{100, 100, 300, 50, 1000, 10, 0, 0}) {
            entries.add(new Entry(new BlockPos(entries.size(), 0, 0), CommonProxy.SYNC_TEST_BE_TYPE.get(), bits(1), new byte[size], new CompoundTag()));
        }
        var packets = SPacketAutoSyncBlockEntities.pack(entries, maxBytes);

        var flattened = packets.stream().flatMap(packet -> packet.entries().stream()).toList();
        check(helper, flattened.equals(entries), "packing reordered or lost entries");
        for (var packet : packets) {
            int bound = packet.entries().stream().mapToInt(Entry::sizeBound).sum();
            check(helper, bound <= maxBytes || packet.entries().size() == 1, "a packet of " + packet.entries().size() + " entries holds " + bound + " bytes");
            int encoded = buffer(level, SPacketAutoSyncBlockEntities.CODEC, packet).readableBytes();
            check(helper, encoded <= bound + 5, "a packet encodes to " + encoded + " bytes, over its bound " + bound);
        }
        check(helper, packets.stream().anyMatch(packet -> packet.entries().equals(List.of(entries.get(4)))), "the oversized entry shares a packet");
        check(helper, packets.size() < entries.size(), "nothing was packed together");
        check(helper, SPacketAutoSyncBlockEntities.pack(List.of(), maxBytes).isEmpty(), "no entries made a packet");
        helper.succeed();
    }

    @GameTest(template = "empty")
    @PrefixGameTestTemplate(false)
    public static void clientAppliesEachEntryToItsBlockEntity(GameTestHelper helper) {
        var level = helper.getLevel();
        var first = new BlockPos(1, 2, 1);
        var second = new BlockPos(3, 2, 1);
        var broken = new BlockPos(5, 2, 1);
        for (var pos : List.of(first, second, broken)) {
            helper.setBlock(pos, SyncTestBlock.BLOCK);
        }
        var corrupt = new Entry(helper.absolutePos(broken), CommonProxy.SYNC_TEST_BE_TYPE.get(), bits(syncIndex("label")),
                new byte[]{(byte) 0xFF}, new CompoundTag());
        var packet = new SPacketAutoSyncBlockEntities(List.of(
                counterUpdate(level, helper.absolutePos(first), 5),
                withType(counterUpdate(level, helper.absolutePos(first), 9), BlockEntityType.FURNACE),
                counterUpdate(level, helper.absolutePos(new BlockPos(7, 2, 1)), 11),
                corrupt,
                counterUpdate(level, helper.absolutePos(second), 7)));

        SPacketAutoSyncBlockEntities.execute(packet, contextOf(level));
        SyncTestBlockEntity firstBe = helper.getBlockEntity(first);
        SyncTestBlockEntity secondBe = helper.getBlockEntity(second);
        check(helper, firstBe.counter == 5, "the first block entity has counter " + firstBe.counter + ", expected 5 and not the mismatched type's 9");
        check(helper, secondBe.counter == 7, "a corrupt entry stopped the ones after it, counter " + secondBe.counter);
        helper.succeed();
    }

    private static Entry counterUpdate(ServerLevel level, BlockPos pos, int counter) {
        var source = new SyncTestBlockEntity(pos, SyncTestBlock.BLOCK.defaultBlockState());
        source.counter = counter;
        var fields = source.getSyncStorage().getSyncFields();
        int index = syncIndex("counter");
        var data = ByteBufUtil.writeCustomData(buf -> fields[index].readSyncToStream(buf), level.registryAccess());
        return new Entry(pos, CommonProxy.SYNC_TEST_BE_TYPE.get(), bits(index), data, new CompoundTag());
    }

    private static int syncIndex(String name) {
        var fields = new SyncTestBlockEntity(BlockPos.ZERO, SyncTestBlock.BLOCK.defaultBlockState()).getSyncStorage().getSyncFields();
        for (int i = 0; i < fields.length; i++) {
            if (fields[i].getKey().getName().equals(name)) return i;
        }
        throw new IllegalArgumentException(name);
    }

    private static Entry withType(Entry entry, BlockEntityType<?> type) {
        return new Entry(entry.pos(), type, entry.changed(), entry.data(), entry.extra());
    }

    private static IPayloadContext contextOf(ServerLevel level) {
        var player = FakePlayerFactory.getMinecraft(level);
        return (IPayloadContext) Proxy.newProxyInstance(IPayloadContext.class.getClassLoader(), new Class<?>[]{IPayloadContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("player")) return player;
                    throw new UnsupportedOperationException(method.getName());
                });
    }

    private static BitSet bits(int... indices) {
        var bits = new BitSet();
        for (int index : indices) {
            bits.set(index);
        }
        return bits;
    }

    private static <T> RegistryFriendlyByteBuf buffer(ServerLevel level, StreamCodec<RegistryFriendlyByteBuf, T> codec, T value) {
        var buf = new RegistryFriendlyByteBuf(Unpooled.buffer(), level.registryAccess(), ConnectionType.NEOFORGE);
        codec.encode(buf, value);
        return buf;
    }

    private static void check(GameTestHelper helper, boolean condition, String message) {
        if (!condition) helper.fail(message);
    }
}
