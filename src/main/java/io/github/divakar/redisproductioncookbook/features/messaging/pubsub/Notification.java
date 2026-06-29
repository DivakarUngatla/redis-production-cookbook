/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import java.time.Instant;

/**
 * Represents a real-time notification published to and received from a Redis
 * Pub/Sub channel.
 *
 * <p>Instances are serialized to JSON as the channel message payload, so every
 * subscriber receives the same structured notification rather than free text.</p>
 *
 * @param id unique notification identifier
 * @param channel Redis channel the notification was published to
 * @param type notification category
 * @param message human-readable notification body
 * @param publishedAt time at which the notification was published
 */
public record Notification(
		String id,
		String channel,
		NotificationType type,
		String message,
		Instant publishedAt) {
}
