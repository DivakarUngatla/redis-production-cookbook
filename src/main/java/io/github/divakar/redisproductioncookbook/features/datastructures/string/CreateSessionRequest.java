/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Request for creating a user session.
 *
 * @param userId identifier of the user who owns the session
 * @param ttlSeconds requested session lifetime in seconds
 */
public record CreateSessionRequest(
		@NotBlank @Size(max = 128) String userId,
		@Positive @Max(2_592_000) long ttlSeconds) {
}
