/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Describes a notification submitted through the notification API for publishing
 * to a Redis Pub/Sub channel.
 *
 * @param channel Redis channel to publish to, e.g. {@code notifications:realtime}
 * @param type notification category
 * @param message human-readable notification body
 */
public record PublishNotificationRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "[a-zA-Z0-9:_-]+", message = "must contain only letters, numbers, ':', '_' or '-'")
		String channel,
		@NotNull NotificationType type,
		@NotBlank @Size(max = 1000) String message) {
}
