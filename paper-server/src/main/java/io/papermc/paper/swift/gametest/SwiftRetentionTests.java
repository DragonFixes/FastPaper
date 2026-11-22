package io.papermc.paper.swift.gametest;

import io.papermc.paper.swift.poi.PoiRetention;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import net.minecraft.world.entity.ai.village.poi.PoiTypes;
import org.slf4j.LoggerFactory;

/**
 * Swift - POI retention (patch 0042) must not leave anything behind besides the POI bytes: an earlier version
 * kept the holder alive with a ticket, which kept village-centre sources of the distance tracker alive too.
 * <p>
 * A meeting point makes a section a village centre source of {@code PoiManager}'s distance tracker, and the
 * source is removed when the POI data of its chunk unloads ({@code moonrise$onUnload}). Retention keeps that
 * data (and so the source) for 600 ticks. {@code sectionsToVillage} answers from the tracker, so it must give
 * the kill-switched answer for the same POI.
 */
final class SwiftRetentionTests {
    private SwiftRetentionTests() {
    }

    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger("SwiftGameTests");

    /** Two identical meeting POIs in unloaded chunks, one loaded with retention off and one with it on. */
    static void villageDistanceAfterLoad(final GameTestHelper helper) {
        final var level = helper.getLevel();
        final PoiManager poi = level.getPoiManager();
        final Holder<PoiType> meeting = level.registryAccess()
            .lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.MEETING);
        final BlockPos without = new BlockPos(200_000, 40, 200_000);
        final BlockPos with = new BlockPos(200_000, 40, 204_000);
        final boolean was = PoiRetention.enabled;
        final int off0;
        final int on0;
        final boolean claimedOff;
        final boolean claimedOn;
        try {
            PoiRetention.enabled = false;
            poi.add(without, meeting);
            claimedOff = poi.take(t -> t.is(PoiTypes.MEETING), (t, p) -> p.equals(without), without, 1).isPresent();
            off0 = poi.sectionsToVillage(SectionPos.of(without));
            PoiRetention.enabled = was; // as configured: on by default, off with -Dswift.poiRetain=false
            poi.add(with, meeting);
            claimedOn = poi.take(t -> t.is(PoiTypes.MEETING), (t, p) -> p.equals(with), with, 1).isPresent();
            on0 = poi.sectionsToVillage(SectionPos.of(with));
        } finally {
            PoiRetention.enabled = was;
        }
        LOG.info("[SwiftGameTests] meeting POIs claimed: retention off {}, retention on {}; village distance at the "
            + "POI's chunk right after the blocking load: retention off {}, retention on {}", claimedOff, claimedOn, off0, on0);
        helper.runAfterDelay(120, () -> {
            final int off1 = poi.sectionsToVillage(SectionPos.of(without));
            final int on1 = poi.sectionsToVillage(SectionPos.of(with));
            LOG.info("[SwiftGameTests] village distance at a claimed meeting POI in an unloaded chunk, after 120 ticks: "
                + "retention off {} -> {}, retention on {} -> {}", off0, off1, on0, on1);
            // keep watching until the retention ticket (600 ticks) expires, to show the source is the ticket's doing
            helper.runAfterDelay(600, () -> {
                try {
                    final int off2 = poi.sectionsToVillage(SectionPos.of(without));
                    final int on2 = poi.sectionsToVillage(SectionPos.of(with));
                    LOG.info("[SwiftGameTests] village distance after 720 ticks (retention ticket expired): "
                        + "retention off {} -> {} -> {}, retention on {} -> {} -> {}", off0, off1, off2, on0, on1, on2);
                    helper.assertTrue(off1 == on1, Component.literal(
                        "sectionsToVillage answers " + on1 + " at a village centre with retention on (it answered " + on0
                            + " for the same POI) where the kill-switched path answers " + off1 + " (it answered " + off0
                            + "); after the 600-tick ticket expires it is " + on2 + " vs " + off2));
                    helper.succeed();
                } finally {
                    poi.remove(without);
                    poi.remove(with);
                }
            });
        });
    }

    /**
     * The mirror must always hold what a disk read returns. Six far chunks get POIs added, removed, claimed and
     * released over several load/unload cycles (each step is a blocking load of an unloaded chunk, so the later
     * steps are answered by the mirror); then every chunk's mirrored tag is compared with a real read
     * ({@code MoonriseRegionFileIO.loadData} with the mirror switched off: pending write or region file).
     */
    static void mirrorDifferential(final GameTestHelper helper) {
        final var level = helper.getLevel();
        final PoiManager poi = level.getPoiManager();
        final Holder<PoiType> meeting = level.registryAccess()
            .lookupOrThrow(Registries.POINT_OF_INTEREST_TYPE).getOrThrow(PoiTypes.MEETING);
        final int n = 6;
        final BlockPos[] a = new BlockPos[n];
        final BlockPos[] b = new BlockPos[n];
        for (int i = 0; i < n; i++) {
            a[i] = new BlockPos(300_000 + i * 512, 40, 300_000);
            b[i] = a[i].offset(5, 3, 7);
        }
        final long hits0 = PoiRetention.MIRROR_HITS.get();
        for (int i = 0; i < n; i++) {
            poi.add(a[i], meeting);
        }
        helper.runAfterDelay(40, () -> {
            for (int i = 0; i < n; i++) {
                final BlockPos at = a[i];
                if (i % 2 == 0) {
                    poi.remove(at);
                } else if (i == 1) {
                    poi.take(t -> t.is(PoiTypes.MEETING), (t, p) -> p.equals(at), at, 1);
                } else {
                    poi.getType(at);
                }
                if (i % 3 == 0) {
                    poi.add(b[i], meeting);
                }
            }
            helper.runAfterDelay(40, () -> {
                final StringBuilder f = new StringBuilder();
                for (int i = 0; i < n; i++) {
                    final boolean wantA = i % 2 != 0;
                    final boolean gotA = poi.getType(a[i]).isPresent();
                    if (gotA != wantA) {
                        f.append(" chunk ").append(i).append(": POI A present=").append(gotA).append(", want ").append(wantA);
                    }
                    if (i == 1) {
                        poi.release(a[i]);
                    }
                    if (i == 5) {
                        poi.add(b[i], meeting);
                    }
                }
                helper.runAfterDelay(50, () -> {
                    final boolean was = PoiRetention.enabled;
                    final var mirror = ((ca.spottedleaf.moonrise.patches.chunk_system.level.ChunkSystemServerLevel) level)
                        .moonrise$getChunkTaskScheduler().swiftPoiMirror;
                    int mirrored = 0;
                    try {
                        for (int i = 0; i < n; i++) {
                            final int cx = a[i].getX() >> 4;
                            final int cz = a[i].getZ() >> 4;
                            final net.minecraft.nbt.CompoundTag[] out = new net.minecraft.nbt.CompoundTag[1];
                            PoiRetention.enabled = true;
                            final boolean known = mirror.serve(ca.spottedleaf.moonrise.common.util.CoordinateUtils.getChunkKey(cx, cz), out);
                            PoiRetention.enabled = false;
                            final net.minecraft.nbt.CompoundTag disk = ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.loadData(
                                level, cx, cz, ca.spottedleaf.moonrise.patches.chunk_system.io.MoonriseRegionFileIO.RegionFileType.POI_DATA,
                                ca.spottedleaf.concurrentutil.util.Priority.BLOCKING);
                            if (!known) {
                                if (was) {
                                    f.append(" chunk ").append(i).append(" not mirrored");
                                }
                                continue;
                            }
                            mirrored++;
                            final boolean same = java.util.Objects.equals(out[0], disk)
                                || (out[0] == null && disk != null && disk.isEmpty()) || (disk == null && out[0] != null && out[0].isEmpty());
                            if (!same) {
                                f.append(" chunk ").append(i).append(": mirror=").append(out[0]).append(" disk=").append(disk);
                            }
                        }
                    } catch (final java.io.IOException e) {
                        f.append(" disk read failed: ").append(e);
                    } finally {
                        PoiRetention.enabled = was;
                        for (int i = 0; i < n; i++) {
                            poi.remove(a[i]);
                            poi.remove(b[i]);
                        }
                    }
                    final long hits = PoiRetention.MIRROR_HITS.get() - hits0;
                    LOG.info("[SwiftGameTests] POI mirror differential: {} chunks mirrored, {} reads answered by the mirror (retention {})",
                        mirrored, hits, was ? "on" : "off");
                    if (was && hits < n) {
                        f.append(" mirror answered only ").append(hits).append(" reads; the test did not exercise it");
                    }
                    if (!f.isEmpty()) {
                        helper.fail(Component.literal("POI mirror differential:" + f));
                        return;
                    }
                    helper.succeed();
                });
            });
        });
    }
}
