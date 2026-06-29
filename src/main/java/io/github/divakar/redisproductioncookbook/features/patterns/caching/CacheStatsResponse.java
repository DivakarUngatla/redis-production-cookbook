/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

/**
 * How many times the slow {@link BookDatabase} was actually touched — a low read count
 * relative to request count is the cache doing its job.
 *
 * @param databaseReads simulated database reads since the last reset
 * @param databaseWrites simulated database writes since the last reset
 */
public record CacheStatsResponse(long databaseReads, long databaseWrites) {
}
