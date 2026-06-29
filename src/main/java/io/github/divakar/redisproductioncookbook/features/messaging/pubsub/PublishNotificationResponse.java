/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import java.time.Instant;

/**
 * API response returned after publishing a notification.
 *
 * <p>{@code subscribersNotified} is the count Redis reports as having received the
 * message at publish time. It is zero when no subscriber is connected, reflecting
 * the at-most-once nature of Pub/Sub.</p>
 *
 * @param id unique notification identifier
 * @param channel Redis channel the notification was published to
 * @param type notification category
 * @param message human-readable notification body
 * @param publishedAt time at which the notification was published
 * @param subscribersNotified number of subscribers that received the message
 */
public record PublishNotificationResponse(
		String id,
		String channel,
		NotificationType type,
		String message,
		Instant publishedAt,
		long subscribersNotified) {
}
