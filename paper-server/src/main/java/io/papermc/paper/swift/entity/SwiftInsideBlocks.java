package io.papermc.paper.swift.entity;

/**
 * Swift - patch 0058: {@code InsideBlockEffectApplier.StepBasedCollector} skips its flush loop when no
 * effect was collected since the last flush. A flush with nothing collected moves nothing, so the result is
 * the same; it ran for every entity every tick (and on every block step) and allocated empty arrays doing it.
 * Kill switch {@code -Dswift.insideEffectSkipEmpty=false}. Tests may flip it.
 */
public final class SwiftInsideBlocks {
    private SwiftInsideBlocks() {
    }

    public static volatile boolean skipEmptyFlush = Boolean.parseBoolean(System.getProperty("swift.insideEffectSkipEmpty", "true"));
}
