/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.locks;

/**
 * Result of a lock release attempt.
 *
 * @param key the logical lock name
 * @param released whether this call released the lock (false if the token did not match)
 */
public record ReleaseLockResponse(String key, boolean released) {
}
