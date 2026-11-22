package io.papermc.paper.swift.level;

/**
 * Swift - noise biome fast path.
 * <p>
 * {@code Level#getNoiseBiome} asks the chunk system for the chunk at status BIOMES, which always takes
 * the chunk-holder hash lookup and returns the ImposterProtoChunk wrapper of a loaded full chunk. When the
 * chunk is a loaded full chunk we read the biome from it directly (same sections, same result).
 * {@code -Dswift.fastNoiseBiome=false} turns it off. Not final so tests can compare both paths.
 */
public final class SwiftBiomeLookup {
    public static volatile boolean fullChunkFastPath = Boolean.parseBoolean(System.getProperty("swift.fastNoiseBiome", "true"));

    private SwiftBiomeLookup() {
    }
}
