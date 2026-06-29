/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * API response returned after appending an order event to the stream.
 *
 * @param entryId stream entry ID assigned by Redis ({@code <ms>-<seq>})
 * @param orderId business identifier of the order
 * @param type order event category
 * @param amount monetary amount associated with the event
 * @param customer customer associated with the order
 * @param occurredAt time the event occurred
 */
public record PublishOrderEventResponse(
		String entryId,
		String orderId,
		OrderEventType type,
		BigDecimal amount,
		String customer,
		Instant occurredAt) {
}
