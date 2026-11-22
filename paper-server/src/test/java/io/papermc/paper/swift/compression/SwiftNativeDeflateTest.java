package io.papermc.paper.swift.compression;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.bukkit.support.environment.Normal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Swift - patch 0057: libdeflate region compression must be interchangeable with java.util.zip in both directions. */
@Normal
public class SwiftNativeDeflateTest {

    @AfterEach
    void restore() {
        SwiftNativeDeflate.enabled = SwiftNativeDeflate.available();
    }

    private static boolean supportedPlatform() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return (os.startsWith("windows") || os.startsWith("linux")) && (arch.equals("amd64") || arch.equals("x86_64"));
    }

    @Test
    void loadsOnSupportedPlatforms() {
        if (supportedPlatform()) {
            assertTrue(SwiftNativeDeflate.available(), "bundled libdeflate must load on " + System.getProperty("os.name"));
        }
    }

    private static byte[] compress(final boolean nativePath, final byte[] raw) throws IOException {
        SwiftNativeDeflate.enabled = nativePath;
        final ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (OutputStream out = RegionFileVersion.VERSION_DEFLATE.wrap(bos)) {
            // write in uneven pieces like NBT serialisation does
            int i = 0;
            final Random r = new Random(raw.length);
            while (i < raw.length) {
                final int n = Math.min(raw.length - i, 1 + r.nextInt(3000));
                if (n == 1) {
                    out.write(raw[i]);
                } else {
                    out.write(raw, i, n);
                }
                i += n;
            }
        }
        return bos.toByteArray();
    }

    /** Outcome of reading: the bytes, or the exception class (corrupt input must fail the same way). */
    private static Object decompress(final boolean nativePath, final byte[] packed) {
        SwiftNativeDeflate.enabled = nativePath;
        try (InputStream in = RegionFileVersion.VERSION_DEFLATE.wrap(new ByteArrayInputStream(packed))) {
            return List.of(in.readAllBytes());
        } catch (final IOException | RuntimeException ex) {
            return ex.getClass();
        }
    }

    private static List<byte[]> payloads() {
        final List<byte[]> out = new ArrayList<>();
        final Random r = new Random(0x5717F7L);
        out.add(new byte[0]);
        out.add(new byte[] {42});
        final byte[] text = "minecraft:stone minecraft:dirt minecraft:grass_block ".repeat(400).getBytes();
        out.add(text);
        final byte[] noise = new byte[300_000];
        r.nextBytes(noise);
        out.add(noise); // incompressible: stored blocks
        final byte[] zeros = new byte[24 << 20];
        out.add(zeros); // ratio ~1000x: forces the decompress buffer to grow many times
        final byte[] mixed = new byte[2_000_000];
        for (int i = 0; i < mixed.length; i++) {
            mixed[i] = (byte) (i % 113 < 70 ? (i >> 7) & 7 : r.nextInt(256));
        }
        out.add(mixed);
        return out;
    }

    @Test
    void bothCodecsReadEachOther() throws IOException {
        assumeTrue(SwiftNativeDeflate.available());
        for (final byte[] raw : payloads()) {
            final byte[] nativePacked = compress(true, raw);
            final byte[] jdkPacked = compress(false, raw);
            for (final byte[] packed : List.of(nativePacked, jdkPacked)) {
                final Object viaJdk = decompress(false, packed);
                final Object viaNative = decompress(true, packed);
                assertTrue(viaJdk instanceof List<?>, "JDK must read it, size " + raw.length);
                assertArrayEquals(raw, (byte[]) ((List<?>) viaJdk).get(0), "JDK read, size " + raw.length);
                assertArrayEquals(raw, (byte[]) ((List<?>) viaNative).get(0), "native read, size " + raw.length);
            }
        }
    }

    @Test
    void corruptAndOddStreamsBehaveLikeTheJdk() throws IOException {
        assumeTrue(SwiftNativeDeflate.available());
        final Random r = new Random(7);
        int cases = 0;
        for (final byte[] raw : payloads()) {
            if (raw.length > 3_000_000) {
                continue;
            }
            final byte[] good = compress(false, raw);
            final List<byte[]> variants = new ArrayList<>();
            for (int k = 0; k < 60 && good.length > 0; k++) {
                final byte[] flipped = good.clone();
                flipped[r.nextInt(flipped.length)] ^= (byte) (1 << r.nextInt(8));
                variants.add(flipped);
                variants.add(java.util.Arrays.copyOf(good, r.nextInt(good.length))); // truncated
            }
            final byte[] trailing = java.util.Arrays.copyOf(good, good.length + 37); // junk after the stream
            for (int i = good.length; i < trailing.length; i++) {
                trailing[i] = (byte) r.nextInt(256);
            }
            variants.add(trailing);
            variants.add(new byte[0]);
            variants.add(new byte[] {0x78}); // header only
            for (final byte[] v : variants) {
                final Object jdk = decompress(false, v);
                final Object nat = decompress(true, v);
                if (jdk instanceof List<?> a && nat instanceof List<?> b) {
                    assertArrayEquals((byte[]) a.get(0), (byte[]) b.get(0));
                } else {
                    assertEquals(jdk, nat, "same failure for a corrupt stream");
                }
                cases++;
            }
        }
        assertTrue(cases > 300);
    }

    @Test
    void nbtThroughRealRegionFile(@TempDir final Path dir) throws IOException {
        assumeTrue(SwiftNativeDeflate.available());
        final RegionStorageInfo info = new RegionStorageInfo("test",
            ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace("overworld")), "chunk");
        final Random r = new Random(11);
        final List<CompoundTag> tags = new ArrayList<>();
        for (int c = 0; c < 40; c++) {
            final CompoundTag tag = new CompoundTag();
            tag.putInt("xPos", c);
            tag.putString("Status", "minecraft:full");
            final ListTag sections = new ListTag();
            for (int s = 0; s < 24; s++) {
                final CompoundTag section = new CompoundTag();
                final long[] data = new long[256];
                for (int i = 0; i < data.length; i++) {
                    data[i] = r.nextInt(4) == 0 ? r.nextLong() : 0x1111111111111111L * (s % 5);
                }
                section.put("data", new LongArrayTag(data));
                sections.add(section);
            }
            tag.put("sections", sections);
            if (c == 39) {
                // > 1 MiB compressed: goes to an external .mcc file
                final long[] noise = new long[200_000];
                for (int i = 0; i < noise.length; i++) {
                    noise[i] = r.nextLong();
                }
                tag.put("noise", new LongArrayTag(noise));
            }
            tags.add(tag);
        }
        final Path regionPath = dir.resolve("r.0.0.mca");
        for (final boolean writeNative : new boolean[] {true, false}) {
            Files.deleteIfExists(regionPath);
            SwiftNativeDeflate.enabled = writeNative;
            try (RegionFile file = new RegionFile(info, regionPath, dir, RegionFileVersion.VERSION_DEFLATE, false)) {
                for (int c = 0; c < tags.size(); c++) {
                    try (DataOutputStream out = file.getChunkDataOutputStream(new ChunkPos(c % 32, c / 32))) {
                        NbtIo.write(tags.get(c), out);
                    }
                }
            }
            for (final boolean readNative : new boolean[] {true, false}) {
                SwiftNativeDeflate.enabled = readNative;
                try (RegionFile file = new RegionFile(info, regionPath, dir, RegionFileVersion.VERSION_DEFLATE, false)) {
                    for (int c = 0; c < tags.size(); c++) {
                        try (DataInputStream in = file.getChunkDataInputStream(new ChunkPos(c % 32, c / 32))) {
                            assertNotNull(in, "chunk " + c);
                            assertEquals(tags.get(c), NbtIo.read(in), "chunk " + c + " write native=" + writeNative + " read native=" + readNative);
                        }
                    }
                }
            }
        }
    }
}
