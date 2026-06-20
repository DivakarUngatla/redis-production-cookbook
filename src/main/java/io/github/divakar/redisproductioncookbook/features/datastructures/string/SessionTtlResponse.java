/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

/**
 * API response describing the remaining lifetime of a session.
 *
 * @param sessionId session identifier
 * @param remainingSeconds remaining lifetime in seconds
 */
public record SessionTtlResponse(String sessionId, long remainingSeconds) {
}
