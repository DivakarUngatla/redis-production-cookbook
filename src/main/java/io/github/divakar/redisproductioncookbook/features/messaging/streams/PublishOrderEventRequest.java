/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Describes an order event submitted through the API for appending to the Redis Stream.
 *
 * @param orderId business identifier of the order
 * @param type order event category
 * @param amount monetary amount associated with the event
 * @param customer customer associated with the order
 */
public record PublishOrderEventRequest(
		@NotBlank @Size(max = 128) String orderId,
		@NotNull OrderEventType type,
		@NotNull @Positive BigDecimal amount,
		@NotBlank @Size(max = 128) String customer) {
}
