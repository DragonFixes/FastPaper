package io.papermc.paper.swift.compression;

import com.mojang.logging.LogUtils;
import io.papermc.paper.swift.SwiftVerify;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.ref.Cleaner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Swift - region file "deflate" (zlib) compression through libdeflate, called with the Foreign Function &amp;
 * Memory API instead of {@code java.util.zip}.
 * <p>
 * The stored format does not change: libdeflate writes and reads standard zlib streams, so region files stay
 * readable by vanilla, older Paper and every region tool, and every existing world reads as before. Only the
 * compressed bytes differ (another encoder picks other matches), never the decompressed chunk data.
 * <p>
 * Chunk data is already a complete buffer in memory on both sides ({@code RegionFile} reads the whole stream
 * into a buffer, and saves collect into a {@code ChunkBuffer}), so each chunk is one native call over a heap
 * array ({@link Linker.Option#critical(boolean) critical} downcall, no copy into native memory).
 * <p>
 * Anything unexpected falls back to the JDK path: a missing library for the OS (ARM, macOS, musl), a failed
 * start-up self test, or a stream libdeflate rejects (it is then decoded by {@link Inflater} exactly as
 * before, so corrupt chunks give the same errors). Kill switch {@code -Dswift.nativeDeflate=false};
 * {@code -Dswift.nativeDeflate.level} (0-12, default 6 = the JDK default level, same file size);
 * {@code -Dswift.nativeDeflate.library=<path>} uses that libdeflate instead of the bundled one.
 */
public final class SwiftNativeDeflate {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final int RESULT_SUCCESS = 0;
    private static final int RESULT_INSUFFICIENT_SPACE = 3;
    /** Decompressed chunks larger than this give up on libdeflate and use the JDK (vanilla's own limit is far below). */
    private static final int MAX_DECOMPRESSED = 1 << 30;
    /** Per-thread scratch buffers above this size are not kept after use. */
    private static final int MAX_RETAINED_SCRATCH = 4 << 20;

    public static final int LEVEL = Math.clamp(Integer.getInteger("swift.nativeDeflate.level", 6), 0, 12);

    private static final @Nullable Natives NATIVES = Boolean.parseBoolean(System.getProperty("swift.nativeDeflate", "true")) ? Natives.load() : null;

    /** True when libdeflate is loaded, passed its self test and is switched on. Tests may flip it. */
    public static volatile boolean enabled = NATIVES != null;

    private static final Cleaner CLEANER = Cleaner.create();
    private static final ThreadLocal<ThreadState> STATE = ThreadLocal.withInitial(ThreadState::new);

    private SwiftNativeDeflate() {
    }

    public static boolean available() {
        return NATIVES != null;
    }

    @FunctionalInterface
    public interface IOFunction<I, O> {
        O apply(I in) throws IOException;
    }

    // ---- RegionFileVersion hooks ----

    /** Input side of {@code RegionFileVersion.VERSION_DEFLATE}; {@code vanilla} is the original wrapper. */
    public static InputStream inflating(final InputStream in, final IOFunction<InputStream, InputStream> vanilla) throws IOException {
        if (!enabled) {
            return vanilla.apply(in);
        }
        final byte[] compressed;
        try (in) {
            compressed = in.readAllBytes();
        }
        final byte[] raw = inflate(compressed, compressed.length);
        if (raw == null) {
            // libdeflate rejected it: let the JDK decode (or fail on) the same bytes, exactly as before
            return vanilla.apply(new ByteArrayInputStream(compressed));
        }
        if (SwiftVerify.ENABLED) {
            verifyInflate(compressed, raw);
        }
        return new ByteArrayInputStream(raw);
    }

    /** Output side of {@code RegionFileVersion.VERSION_DEFLATE}; {@code vanilla} is the original wrapper. */
    public static OutputStream deflating(final OutputStream out, final IOFunction<OutputStream, OutputStream> vanilla) throws IOException {
        if (!enabled) {
            return vanilla.apply(out);
        }
        return new DeflatingOutputStream(out);
    }

    // ---- one-shot codec ----

    /** Decompresses one zlib stream; null when libdeflate cannot (caller falls back to the JDK). */
    public static byte @Nullable [] inflate(final byte[] src, final int len) {
        final Natives n = NATIVES;
        if (n == null) {
            return null;
        }
        final ThreadState state = STATE.get();
        try {
            int cap = Math.max(state.out.length, Math.min(MAX_DECOMPRESSED, Math.max(65536, len * 8)));
            while (true) {
                if (state.out.length < cap) {
                    state.out = new byte[cap];
                }
                final byte[] out = state.out;
                final int rc = (int) n.decompress.invokeExact(state.decompressor(n), MemorySegment.ofArray(src), (long) len,
                    MemorySegment.ofArray(out), (long) out.length, MemorySegment.NULL, MemorySegment.ofArray(state.sizeOut));
                if (rc == RESULT_SUCCESS) {
                    final byte[] ret = Arrays.copyOf(out, (int) state.sizeOut[0]);
                    state.trimOut();
                    return ret;
                }
                if (rc != RESULT_INSUFFICIENT_SPACE || cap >= MAX_DECOMPRESSED) {
                    state.trimOut();
                    return null;
                }
                cap = (int) Math.min(MAX_DECOMPRESSED, cap * 2L);
            }
        } catch (final Throwable thr) {
            state.trimOut();
            return null;
        }
    }

    /** Compresses {@code len} bytes of {@code src} as one zlib stream into {@code out}. */
    public static void deflate(final byte[] src, final int len, final OutputStream out) throws IOException {
        final Natives n = NATIVES;
        if (n != null) {
            final ThreadState state = STATE.get();
            try {
                final MemorySegment c = state.compressor(n);
                final int bound = (int) Math.min(Integer.MAX_VALUE - 16, (long) n.bound.invokeExact(c, (long) len));
                if (state.comp.length < bound) {
                    state.comp = new byte[bound];
                }
                final byte[] dst = state.comp;
                final long written = (long) n.compress.invokeExact(c, MemorySegment.ofArray(src), (long) len,
                    MemorySegment.ofArray(dst), (long) dst.length);
                if (written > 0) {
                    try {
                        if (SwiftVerify.ENABLED) {
                            verifyDeflate(src, len, dst, (int) written);
                        }
                        out.write(dst, 0, (int) written);
                    } finally {
                        if (state.comp.length > MAX_RETAINED_SCRATCH) {
                            state.comp = new byte[0];
                        }
                    }
                    return;
                }
            } catch (final IOException | RuntimeException ex) {
                throw ex;
            } catch (final Throwable thr) {
                // fall through to the JDK
            }
        }
        jdkDeflate(src, len, out);
    }

    static void jdkDeflate(final byte[] src, final int len, final OutputStream out) throws IOException {
        final Deflater deflater = new Deflater(LEVEL);
        try {
            deflater.setInput(src, 0, len);
            deflater.finish();
            final byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                final int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
        } finally {
            deflater.end();
        }
    }

    static byte[] jdkInflate(final byte[] src, final int len) throws DataFormatException {
        final Inflater inflater = new Inflater();
        try {
            inflater.setInput(src, 0, len);
            final ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, len * 4));
            final byte[] buf = new byte[16384];
            while (!inflater.finished()) {
                final int n = inflater.inflate(buf);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    throw new DataFormatException("truncated zlib stream");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static void verifyInflate(final byte[] compressed, final byte[] raw) {
        SwiftVerify.checked();
        try {
            final byte[] expected = jdkInflate(compressed, compressed.length);
            if (!Arrays.equals(expected, raw)) {
                SwiftVerify.mismatch("nativeDeflate.inflate", "jdk " + expected.length + " bytes, native " + raw.length + " bytes");
            }
        } catch (final DataFormatException ex) {
            SwiftVerify.mismatch("nativeDeflate.inflate", "native decoded a stream the JDK rejects: " + ex.getMessage());
        }
    }

    private static void verifyDeflate(final byte[] src, final int len, final byte[] dst, final int written) {
        SwiftVerify.checked();
        try {
            final byte[] back = jdkInflate(dst, written);
            if (!Arrays.equals(back, 0, back.length, src, 0, len)) {
                SwiftVerify.mismatch("nativeDeflate.deflate", "JDK reads back " + back.length + " bytes, input was " + len);
            }
        } catch (final DataFormatException ex) {
            SwiftVerify.mismatch("nativeDeflate.deflate", "JDK cannot read native output: " + ex.getMessage());
        }
    }

    /** Collects the whole uncompressed stream, compresses it in one call on close, then closes the target. */
    static final class DeflatingOutputStream extends ByteArrayOutputStream {
        private final OutputStream target;
        private boolean closed;

        DeflatingOutputStream(final OutputStream target) {
            super(16384);
            this.target = target;
        }

        @Override
        public void flush() throws IOException {
            // DeflaterOutputStream without syncFlush emits nothing on flush either; just pass it on
            this.target.flush();
        }

        @Override
        public void close() throws IOException {
            if (this.closed) {
                return;
            }
            this.closed = true;
            try (this.target) {
                deflate(this.buf, this.count, this.target);
            }
        }
    }

    // ---- per-thread libdeflate objects ----

    private static final class ThreadState {
        private @Nullable Handles handles;
        byte[] out = new byte[0];
        byte[] comp = new byte[0];
        final long[] sizeOut = new long[1];

        MemorySegment decompressor(final Natives n) throws Throwable {
            return this.handles(n).decompressor;
        }

        MemorySegment compressor(final Natives n) throws Throwable {
            return this.handles(n).compressor;
        }

        private Handles handles(final Natives n) throws Throwable {
            Handles h = this.handles;
            if (h == null) {
                final MemorySegment c = (MemorySegment) n.allocCompressor.invokeExact(LEVEL);
                final MemorySegment d = (MemorySegment) n.allocDecompressor.invokeExact();
                if (c.equals(MemorySegment.NULL) || d.equals(MemorySegment.NULL)) {
                    throw new OutOfMemoryError("libdeflate allocation failed");
                }
                h = this.handles = new Handles(c, d);
                // free the native objects once this thread (and so this state) is gone
                CLEANER.register(this, new Free(n, c.address(), d.address()));
            }
            return h;
        }

        void trimOut() {
            if (this.out.length > MAX_RETAINED_SCRATCH) {
                this.out = new byte[0];
            }
        }
    }

    private record Handles(MemorySegment compressor, MemorySegment decompressor) {
    }

    private record Free(Natives n, long compressor, long decompressor) implements Runnable {
        @Override
        public void run() {
            try {
                this.n.freeCompressor.invokeExact(MemorySegment.ofAddress(this.compressor));
                this.n.freeDecompressor.invokeExact(MemorySegment.ofAddress(this.decompressor));
            } catch (final Throwable ignored) {
            }
        }
    }

    // ---- library loading ----

    private record Natives(MethodHandle allocCompressor, MethodHandle allocDecompressor, MethodHandle freeCompressor,
                           MethodHandle freeDecompressor, MethodHandle compress, MethodHandle bound, MethodHandle decompress) {

        static @Nullable Natives load() {
            try {
                final Path lib = locate();
                if (lib == null) {
                    LOGGER.info("[Swift] No bundled libdeflate for {}/{}; region compression uses java.util.zip", System.getProperty("os.name"), System.getProperty("os.arch"));
                    return null;
                }
                final Linker linker = Linker.nativeLinker();
                final SymbolLookup lookup = SymbolLookup.libraryLookup(lib, Arena.global());
                final Linker.Option critical = Linker.Option.critical(true);
                final ValueLayout.OfLong sizeT = ValueLayout.JAVA_LONG; // size_t on every 64-bit target we ship
                final Natives n = new Natives(
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_alloc_compressor"), FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT)),
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_alloc_decompressor"), FunctionDescriptor.of(ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_free_compressor"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_free_decompressor"), FunctionDescriptor.ofVoid(ValueLayout.ADDRESS)),
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_zlib_compress"),
                        FunctionDescriptor.of(sizeT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, sizeT, ValueLayout.ADDRESS, sizeT), critical),
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_zlib_compress_bound"),
                        FunctionDescriptor.of(sizeT, ValueLayout.ADDRESS, sizeT), critical),
                    // enum libdeflate_result libdeflate_zlib_decompress_ex(d, in, in_nbytes, out, out_nbytes_avail, size_t *actual_in, size_t *actual_out)
                    linker.downcallHandle(lookup.findOrThrow("libdeflate_zlib_decompress_ex"),
                        FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS, sizeT, ValueLayout.ADDRESS, sizeT, ValueLayout.ADDRESS, ValueLayout.ADDRESS), critical)
                );
                if (!selfTest(n)) {
                    LOGGER.warn("[Swift] libdeflate failed its self test; region compression uses java.util.zip");
                    return null;
                }
                LOGGER.info("[Swift] Region compression uses libdeflate through FFM (level {})", LEVEL);
                return n;
            } catch (final Throwable thr) {
                LOGGER.warn("[Swift] Could not load libdeflate ({}); region compression uses java.util.zip", thr.toString());
                return null;
            }
        }

        private static @Nullable Path locate() throws IOException {
            final String override = System.getProperty("swift.nativeDeflate.library");
            if (override != null && !override.isBlank()) {
                return Path.of(override);
            }
            final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
            if (!arch.equals("amd64") && !arch.equals("x86_64")) {
                return null;
            }
            final String resource;
            final String suffix;
            if (os.startsWith("windows")) {
                resource = "windows_x86_64/libdeflate.dll";
                suffix = ".dll";
            } else if (os.startsWith("linux")) {
                resource = "linux_x86_64/libdeflate.so";
                suffix = ".so";
            } else {
                return null;
            }
            try (InputStream in = SwiftNativeDeflate.class.getResourceAsStream("/io/papermc/paper/swift/natives/" + resource)) {
                if (in == null) {
                    return null;
                }
                final Path tmp = Files.createTempFile("swift-libdeflate-", suffix);
                tmp.toFile().deleteOnExit();
                Files.copy(in, tmp, StandardCopyOption.REPLACE_EXISTING);
                return tmp;
            }
        }

        /** Both directions on a chunk-like sample must agree with the JDK before anything real goes through it. */
        private static boolean selfTest(final Natives n) throws Throwable {
            final byte[] sample = new byte[200_000];
            final java.util.Random random = new java.util.Random(0x5717F7L);
            for (int i = 0; i < sample.length; i++) {
                sample[i] = (byte) (i % 97 < 60 ? (i >> 6) & 0x0F : random.nextInt(256)); // mixed runs and noise
            }
            final MemorySegment c = (MemorySegment) n.allocCompressor.invokeExact(LEVEL);
            final MemorySegment d = (MemorySegment) n.allocDecompressor.invokeExact();
            try {
                final byte[] packed = new byte[(int) (long) n.bound.invokeExact(c, (long) sample.length)];
                final long w = (long) n.compress.invokeExact(c, MemorySegment.ofArray(sample), (long) sample.length, MemorySegment.ofArray(packed), (long) packed.length);
                if (w <= 0 || !Arrays.equals(jdkInflate(packed, (int) w), sample)) {
                    return false;
                }
                final java.io.ByteArrayOutputStream jdk = new java.io.ByteArrayOutputStream();
                jdkDeflate(sample, sample.length, jdk);
                final byte[] jdkPacked = jdk.toByteArray();
                final byte[] back = new byte[sample.length];
                final long[] size = new long[1];
                final int rc = (int) n.decompress.invokeExact(d, MemorySegment.ofArray(jdkPacked), (long) jdkPacked.length,
                    MemorySegment.ofArray(back), (long) back.length, MemorySegment.NULL, MemorySegment.ofArray(size));
                return rc == RESULT_SUCCESS && size[0] == sample.length && Arrays.equals(back, sample);
            } finally {
                n.freeCompressor.invokeExact(c);
                n.freeDecompressor.invokeExact(d);
            }
        }
    }
}
