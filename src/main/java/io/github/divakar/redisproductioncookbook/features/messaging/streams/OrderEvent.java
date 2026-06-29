/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Represents an order event appended to and consumed from the Redis Stream.
 *
 * <p>The business fields are serialized to JSON and stored as the {@code payload} field of
 * a stream entry, alongside a separate {@code type} field for routing. The
 * {@code entryId} is the stream-assigned ID ({@code <ms>-<seq>}); it is {@code null}
 * before the entry is appended and populated once Redis returns the generated ID.</p>
 *
 * @param entryId stream entry ID assigned by Redis, or {@code null} before append
 * @param orderId business identifier of the order
 * @param type order event category
 * @param amount monetary amount associated with the event
 * @param customer customer associated with the order
 * @param occurredAt time the event occurred
 */
public record OrderEvent(
		String entryId,
		String orderId,
		OrderEventType type,
		BigDecimal amount,
		String customer,
		Instant occurredAt) {

	/**
	 * Returns a copy of this event with the given stream entry ID.
	 *
	 * @param entryId stream entry ID assigned by Redis
	 * @return a copy carrying the entry ID
	 */
	public OrderEvent withEntryId(String entryId) {
		return new OrderEvent(entryId, orderId, type, amount, customer, occurredAt);
	}
}
