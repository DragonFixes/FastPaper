package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.compression.SwiftNativeDeflate;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.SerializableChunkData;

/**
 * Swift - patch 0057: real chunk save data from this server goes through region "deflate" compression with
 * libdeflate and with java.util.zip, and each codec must read the other's output back to the identical bytes.
 */
public final class SwiftNativeDeflateTests {
    private SwiftNativeDeflateTests() {
    }

    public static void realChunksRoundTrip(final GameTestHelper helper) {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        final boolean supported = (os.startsWith("windows") || os.startsWith("linux")) && (arch.equals("amd64") || arch.equals("x86_64"));
        final boolean wanted = Boolean.parseBoolean(System.getProperty("swift.nativeDeflate", "true"));
        if (supported && wanted && !SwiftNativeDeflate.available()) {
            helper.fail(Component.literal("libdeflate did not load on a supported platform"));
            return;
        }

        // make the chunk data less uniform than the flat world: a few block types and a block entity
        helper.setBlock(new BlockPos(1, 1, 1), Blocks.CHEST);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.OAK_LOG);
        helper.setBlock(new BlockPos(3, 1, 1), Blocks.REDSTONE_WIRE);
        helper.setBlock(new BlockPos(4, 1, 1), Blocks.WATER);

        final ServerLevel level = helper.getLevel();
        final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        final boolean before = SwiftNativeDeflate.enabled;
        int chunks = 0;
        try {
            for (int dx = -4; dx <= 4; dx++) {
                for (int dz = -4; dz <= 4; dz++) {
                    final LevelChunk chunk = level.getChunkSource().getChunkNow((origin.getX() >> 4) + dx, (origin.getZ() >> 4) + dz);
                    if (chunk == null) {
                        continue;
                    }
                    final CompoundTag tag = SerializableChunkData.copyOf(level, chunk).write();
                    final ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
                    NbtIo.write(tag, new DataOutputStream(rawOut));
                    final byte[] raw = rawOut.toByteArray();

                    for (final boolean writeNative : new boolean[] {true, false}) {
                        for (final boolean readNative : new boolean[] {true, false}) {
                            SwiftNativeDeflate.enabled = writeNative && SwiftNativeDeflate.available();
                            final ByteArrayOutputStream packed = new ByteArrayOutputStream();
                            try (DataOutputStream out = new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap((OutputStream) packed))) {
                                NbtIo.write(tag, out); // the real save path: many small writes
                            }
                            SwiftNativeDeflate.enabled = readNative && SwiftNativeDeflate.available();
                            final byte[] back;
                            try (InputStream in = RegionFileVersion.VERSION_DEFLATE.wrap((InputStream) new ByteArrayInputStream(packed.toByteArray()))) {
                                back = in.readAllBytes();
                            }
                            if (!Arrays.equals(raw, back)) {
                                helper.fail(Component.literal("chunk " + chunk.getPos() + " write native=" + writeNative + " read native=" + readNative
                                    + ": " + raw.length + " bytes in, " + back.length + " bytes back"));
                                return;
                            }
                            final CompoundTag reread = NbtIo.read(new DataInputStream(new ByteArrayInputStream(back)));
                            if (!tag.equals(reread)) {
                                helper.fail(Component.literal("chunk " + chunk.getPos() + " NBT differs after the round trip"));
                                return;
                            }
                        }
                    }
                    chunks++;
                }
            }
        } catch (final Exception ex) {
            helper.fail(Component.literal("round trip threw " + ex));
            return;
        } finally {
            SwiftNativeDeflate.enabled = before;
        }
        if (chunks == 0) {
            helper.fail(Component.literal("no loaded chunks around the test"));
            return;
        }
        helper.succeed();
    }
}
