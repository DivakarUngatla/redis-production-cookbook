/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

import java.time.Instant;

/**
 * Represents an event stored in a user's recent activity feed.
 *
 * @param eventId unique event identifier
 * @param userId identifier of the user who owns the feed
 * @param eventType activity category
 * @param description human-readable event description
 * @param createdAt time at which the activity occurred
 */
public record ActivityEvent(
		String eventId,
		String userId,
		ActivityEventType eventType,
		String description,
		Instant createdAt) {
}
