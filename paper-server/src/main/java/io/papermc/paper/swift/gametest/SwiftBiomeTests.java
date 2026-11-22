package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.level.SwiftBiomeLookup;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/**
 * Swift - parity tests for the noise biome fast path ({@link SwiftBiomeLookup}).
 */
final class SwiftBiomeTests {
    private static final String[] BIOMES = {"minecraft:river", "minecraft:desert", "minecraft:jungle", "minecraft:snowy_plains"};

    private SwiftBiomeTests() {
    }

    /**
     * Paints a 3D checkerboard of biomes (4-block cells, the biome resolution) over the arena and the chunk
     * borders it crosses, then requires {@code Level#getNoiseBiome} to return the same holder with the fast
     * path on and off at every quart position, plus far-away unloaded positions (uncached noise path).
     */
    static void differential(final GameTestHelper helper) {
        final ServerLevel level = helper.getLevel();
        final int sizeX = 24;
        final int sizeZ = 24;
        for (int cx = 0; cx < sizeX / 4; cx++) {
            for (int cz = 0; cz < sizeZ / 4; cz++) {
                for (int cy = 0; cy < 4; cy++) {
                    final BlockPos a = helper.absolutePos(new BlockPos(cx * 4, cy * 4 - 4, cz * 4));
                    final BlockPos b = a.offset(3, 3, 3);
                    final String biome = BIOMES[(cx + cz * 2 + cy * 3) % BIOMES.length];
                    SwiftNbtTests.run(helper, "fillbiome " + a.getX() + " " + a.getY() + " " + a.getZ() + " "
                        + b.getX() + " " + b.getY() + " " + b.getZ() + " " + biome);
                }
            }
        }
        helper.runAfterDelay(2, () -> {
            final boolean was = SwiftBiomeLookup.fullChunkFastPath;
            final BlockPos origin = helper.absolutePos(BlockPos.ZERO);
            final int qx0 = (origin.getX() >> 2) - 8;
            final int qz0 = (origin.getZ() >> 2) - 8;
            final int qy0 = level.getMinY() >> 2;
            final int qy1 = (level.getMaxY() >> 2);
            int checked = 0;
            int fullHits = 0;
            final Set<Long> chunks = new HashSet<>();
            final Set<Holder<Biome>> seen = new HashSet<>();
            final StringBuilder f = new StringBuilder();
            try {
                for (int qx = qx0; qx < qx0 + 6 + 16; qx++) {
                    for (int qz = qz0; qz < qz0 + 6 + 16; qz++) {
                        final boolean loaded = level.getChunkIfLoadedImmediately(qx >> 2, qz >> 2) != null;
                        if (loaded) {
                            chunks.add(((long) (qx >> 2) << 32) ^ ((qz >> 2) & 0xFFFFFFFFL));
                        }
                        for (int qy = qy0 - 2; qy <= qy1 + 2; qy++) { // includes out-of-range heights
                            SwiftBiomeLookup.fullChunkFastPath = false;
                            final Holder<Biome> slow = level.getNoiseBiome(qx, qy, qz);
                            SwiftBiomeLookup.fullChunkFastPath = true;
                            final Holder<Biome> fast = level.getNoiseBiome(qx, qy, qz);
                            checked++;
                            if (loaded) {
                                fullHits++;
                            }
                            seen.add(fast);
                            if (fast != slow && f.length() < 2000) {
                                f.append(" q=").append(qx).append(',').append(qy).append(',').append(qz)
                                    .append(" fast=").append(fast.getRegisteredName()).append(" slow=").append(slow.getRegisteredName());
                            }
                        }
                    }
                }
                // unloaded, far away: both paths must fall back to the generator's noise biome
                for (int k = 0; k < 64; k++) {
                    final int qx = qx0 + 40_000 + k * 37;
                    final int qz = qz0 - 40_000 + k * 53;
                    SwiftBiomeLookup.fullChunkFastPath = false;
                    final Holder<Biome> slow = level.getNoiseBiome(qx, 16, qz);
                    SwiftBiomeLookup.fullChunkFastPath = true;
                    final Holder<Biome> fast = level.getNoiseBiome(qx, 16, qz);
                    checked++;
                    if (fast != slow && f.length() < 2000) {
                        f.append(" far q=").append(qx).append(",16,").append(qz);
                    }
                }
            } finally {
                SwiftBiomeLookup.fullChunkFastPath = was;
            }
            LoggerHolder.log("[SwiftBiome] checked " + checked + " lookups, " + fullHits + " in full chunks over " + chunks.size()
                + " chunks, " + seen.size() + " distinct biomes");
            if (chunks.size() < 2 || seen.size() < BIOMES.length) {
                helper.fail(Component.literal("biome differential is vacuous: chunks=" + chunks.size() + " biomes=" + seen.size()));
                return;
            }
            if (!f.isEmpty()) {
                helper.fail(Component.literal("noise biome mismatch:" + f));
                return;
            }
            helper.succeed();
        });
    }

    private static final class LoggerHolder {
        private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("SwiftGameTests");

        static void log(final String msg) {
            LOGGER.info(msg);
        }
    }
}
