/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.streams;

import java.util.Map;

/**
 * API view of a consumer group's pending (delivered-but-unacknowledged) entries.
 *
 * @param group consumer group name
 * @param totalPending total number of pending entries across all consumers
 * @param minEntryId smallest pending entry ID, or {@code null} when none pending
 * @param maxEntryId largest pending entry ID, or {@code null} when none pending
 * @param perConsumer count of pending entries per consumer
 */
public record PendingSummaryResponse(
		String group,
		long totalPending,
		String minEntryId,
		String maxEntryId,
		Map<String, Long> perConsumer) {
}
