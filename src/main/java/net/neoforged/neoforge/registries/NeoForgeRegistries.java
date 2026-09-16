/*
 * Copyright (c) Forge Development LLC and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.neoforge.registries;

import net.minecraft.core.Registry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.NewRegistryEvent;
import net.minecraftforge.registries.RegistryBuilder;
import net.neoforged.neoforge.attachment.AttachmentType;

/**
 * Attachment types registry. <p>
 * tutorial: <pre>{@code
 * // 新建一个数据附件的延迟注册器
 * // 注意：你只能使用ResourceKey（ATTACHMENT_TYPES_KEY）而不能使用ForgeRegistry（ATTACHMENT_TYPES）
 * public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
 *             DeferredRegister.create(NeoForgeRegistries.ATTACHMENT_TYPES_KEY, MOD_ID);
 * public static final Supplier<AttachmentType<TestData>> TEST_ATTACHMENT_TYPE =
 *         ATTACHMENT_TYPES.register("test", () -> AttachmentType.builder(TestData::new)
 *                     .serialize(TestData.CODEC)
 *                     .sync(TestData.STREAM_CODEC)
 *                     .copyOnDeath()
 *                     .build());
 *
 *     @Setter @Getter @Accessors(chain = true)
 *     public static class TestData implements IPersistedSerializable {
 *         @Persisted
 *         int testInt;
 *
 *         public static final Codec<TestData> CODEC = PersistedParser.createCodec(TestData::new);
 *         public static final StreamCodec<ByteBuf, TestData> STREAM_CODEC =
 *                 PersistedParser.createStreamCodec(TestData::new);
 *     }
 * }</pre>
 */
public class NeoForgeRegistries {
    public static final String MOD_ID = "neoforge";
    // NeoForge
    public static final ResourceKey<Registry<AttachmentType<?>>> ATTACHMENT_TYPES_KEY =
            ResourceKey.createRegistryKey(new ResourceLocation(MOD_ID, "attachment_types")) ;
    // Custom NeoForge registries
    public static ForgeRegistry<AttachmentType<?>> ATTACHMENT_TYPES;

    public static void newRegistry(NewRegistryEvent event) {
        event.create(new RegistryBuilder<AttachmentType<?>>().setName(ATTACHMENT_TYPES_KEY.location()),
                registry -> ATTACHMENT_TYPES = (ForgeRegistry<AttachmentType<?>>) registry);
    }
}
