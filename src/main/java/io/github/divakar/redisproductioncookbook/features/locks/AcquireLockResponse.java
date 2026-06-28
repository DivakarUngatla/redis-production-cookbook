/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.locks;

/**
 * Result of a lock acquisition attempt.
 *
 * @param key the logical lock name
 * @param acquired whether the lock was acquired by this call
 * @param token the owner token to present when releasing, or {@code null} if not acquired
 * @param ttlMillis the requested lease duration in milliseconds
 */
public record AcquireLockResponse(String key, boolean acquired, String token, long ttlMillis) {
}
