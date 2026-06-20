/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;

/**
 * Request for renewing an existing session.
 *
 * @param ttlSeconds new lifetime measured from the renewal time, in seconds
 */
public record ExtendSessionRequest(
		@Positive @Max(2_592_000) long ttlSeconds) {
}
