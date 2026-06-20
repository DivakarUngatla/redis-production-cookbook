/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import java.time.Instant;

/**
 * Represents an application session stored as a JSON Redis String.
 *
 * @param sessionId cryptographically secure session identifier
 * @param userId identifier of the authenticated user
 * @param createdAt time at which the session was created
 * @param expiresAt time at which Redis should expire the session
 */
public record UserSession(
		String sessionId,
		String userId,
		Instant createdAt,
		Instant expiresAt) {
}
