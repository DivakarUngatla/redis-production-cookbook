/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.locks;

import jakarta.validation.constraints.NotBlank;

/**
 * Request body for releasing a lock, carrying the owner token proving ownership.
 *
 * @param token the token returned when the lock was acquired
 */
public record ReleaseLockRequest(@NotBlank String token) {
}
