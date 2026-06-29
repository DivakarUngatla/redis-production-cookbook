/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.locks;

/**
 * Result of running a guarded critical section under the lock.
 *
 * @param key the logical lock name
 * @param ran whether the critical section ran (false means the lock was held by someone else)
 * @param heldMillis how long the critical section held the lock
 */
public record RunUnderLockResponse(String key, boolean ran, long heldMillis) {
}
