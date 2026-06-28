/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.locks;

/**
 * Thrown when a lock could not be acquired within the attempt, so the guarded action was not
 * run.
 */
public class LockNotAcquiredException extends RuntimeException {

	public LockNotAcquiredException(String key) {
		super("Could not acquire lock: " + key);
	}
}
