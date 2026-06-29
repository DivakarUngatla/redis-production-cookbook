/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.locks;

/**
 * Proof of ownership for an acquired lock.
 *
 * <p>The {@code value} is a unique token written as the lock's Redis value. Only a holder
 * presenting the matching token may release or renew the lock, which is what makes those
 * operations safe.</p>
 *
 * @param key the logical lock name (without the Redis key prefix)
 * @param value the unique owner token stored as the lock's value
 */
public record LockToken(String key, String value) {
}
