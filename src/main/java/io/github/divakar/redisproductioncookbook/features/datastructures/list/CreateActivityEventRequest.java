/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request for appending an event to a user's activity feed.
 *
 * @param userId identifier of the user who owns the feed
 * @param eventType activity category
 * @param description human-readable event description
 */
public record CreateActivityEventRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "[a-zA-Z0-9_-]+", message = "must contain only letters, numbers, '_' or '-'")
		String userId,
		@NotNull ActivityEventType eventType,
		@NotBlank @Size(max = 500) String description) {
}
