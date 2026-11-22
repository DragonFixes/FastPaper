package io.papermc.paper.swift.poi;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.CompoundTag;
import org.jspecify.annotations.Nullable;

/**
 * Swift - serve repeated blocking POI loads from memory instead of disk.
 * <p>
 * {@code PoiManager#getOrLoad} on a chunk without loaded POI data makes Moonrise's
 * {@code ChunkHolderManager#loadPoiChunk} add a ticket, park the main thread on a disk read and drop
 * the ticket straight away; the data unloads again with the holder, so code that asks every tick
 * (villagers' {@code PoiCompetitorScan} for a job site that is far away, which nothing ever
 * invalidates) re-reads it from disk every tick. In profile qJwhW316qQ that was 0.54 ms/tick of the
 * main thread waiting.
 * <p>
 * For {@link #RETAIN_TICKS} ticks after such a load, a per-world {@link Mirror} keeps a copy of that
 * chunk's POI data as the disk holds it: the tag the disk read returned, replaced by every POI save of
 * the chunk after that. A later read of the chunk's POI data with no read or write in flight is answered
 * from the mirror, so the bytes are the ones the disk read would have returned. Nothing else changes:
 * the chunk holder, its tickets, the load and unload of the POI data (and with it the village distance
 * tracker) happen exactly as without the mirror. An earlier version kept the holder alive with a timed
 * ticket instead, which also kept village-centre sources alive 600 ticks longer than vanilla.
 * Disable with {@code -Dswift.poiRetain=false}.
 */
public final class PoiRetention {
    public static final long RETAIN_TICKS = 600L;
    /** Upper bound on mirrored chunks per world; past it the mirror is emptied (reads go to disk again). */
    private static final int MAX_ENTRIES = 8192;
    /** Not final so tests can compare both paths inside one server. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("swift.poiRetain", "true"));
    /** Blocking POI loads (the main thread waited for the POI data of a chunk), for tests and diagnostics. */
    public static final AtomicLong BLOCKING_LOADS = new AtomicLong();
    /** POI data reads answered from the mirror instead of the disk. */
    public static final AtomicLong MIRROR_HITS = new AtomicLong();
    /** POI data reads that went to the region file (no mirror entry and no read or write in flight). */
    public static final AtomicLong DISK_READS = new AtomicLong();
    /** Nanoseconds the main thread spent parked in blocking POI loads. */
    public static final AtomicLong WAIT_NANOS = new AtomicLong();

    private PoiRetention() {
    }

    private static long now() {
        return net.minecraft.server.MinecraftServer.currentTick;
    }

    /** One world's mirrored POI data, keyed by chunk key. All methods may be called from any thread. */
    public static final class Mirror {
        private static final class Entry {
            long expires;
            boolean known; // tag holds the disk's content (null tag = no POI data on disk)
            long diskReads; // region-file reads of this chunk's POI data while mirrored (tests, diagnostics)
            @Nullable CompoundTag tag;
        }

        private final Long2ObjectOpenHashMap<Entry> entries = new Long2ObjectOpenHashMap<>();
        private int marks;

        /** A blocking load of this chunk's POI data is about to start: mirror the chunk from now on. */
        public synchronized void markBlockingLoad(final long chunkKey) {
            if (!enabled) {
                return;
            }
            final long now = now();
            if ((++this.marks & 255) == 0) {
                this.entries.long2ObjectEntrySet().removeIf((final Long2ObjectMap.Entry<Entry> e) -> e.getValue().expires < now);
            }
            if (this.entries.size() >= MAX_ENTRIES) {
                this.entries.clear();
            }
            Entry entry = this.entries.get(chunkKey);
            if (entry == null) {
                this.entries.put(chunkKey, entry = new Entry());
            }
            entry.expires = now + RETAIN_TICKS;
        }

        /** The POI data of a chunk was read (from disk, an in-flight write, or this mirror). */
        public synchronized void onRead(final long chunkKey, final @Nullable CompoundTag data) {
            final Entry entry = this.entries.get(chunkKey);
            if (entry != null) {
                entry.tag = data == null ? null : data.copy();
                entry.known = true;
            }
        }

        /** A read of this chunk's POI data went to the region file. */
        public synchronized void onDiskRead(final long chunkKey) {
            final Entry entry = this.entries.get(chunkKey);
            if (entry != null) {
                entry.diskReads++;
            }
        }

        /** Region-file reads of a mirrored chunk's POI data (0 when the chunk is not mirrored). */
        public synchronized long diskReads(final long chunkKey) {
            final Entry entry = this.entries.get(chunkKey);
            return entry == null ? 0L : entry.diskReads;
        }

        /** The POI data of a chunk was scheduled to be written to disk (a null tag deletes it). */
        public synchronized void onWrite(final long chunkKey, final @Nullable CompoundTag data) {
            final Entry entry = this.entries.get(chunkKey);
            if (entry != null) {
                entry.tag = data == null ? null : data.copy();
                entry.known = true;
            }
        }

        /**
         * Answers a read from the mirror. Returns false when the chunk is not mirrored (or expired);
         * otherwise stores a private copy of the data (null = no POI data on disk) in {@code out[0]}.
         */
        public synchronized boolean serve(final long chunkKey, final @Nullable CompoundTag[] out) {
            if (!enabled) {
                return false;
            }
            final Entry entry = this.entries.get(chunkKey);
            if (entry == null || !entry.known) {
                return false;
            }
            if (entry.expires < now()) {
                this.entries.remove(chunkKey);
                return false;
            }
            out[0] = entry.tag == null ? null : entry.tag.copy();
            MIRROR_HITS.incrementAndGet();
            return true;
        }
    }
}
