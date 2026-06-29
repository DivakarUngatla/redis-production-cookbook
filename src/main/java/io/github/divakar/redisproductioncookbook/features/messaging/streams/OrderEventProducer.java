/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Appends order events to the Redis Stream with {@code XADD}.
 *
 * <p>Each entry stores two fields: a {@code type} for routing/inspection and a JSON
 * {@code payload} carrying the full event. Redis assigns a time-ordered entry ID
 * ({@code <ms>-<seq>}) which is returned to the caller.</p>
 */
@Service
public class OrderEventProducer {

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Creates the order event producer.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param objectMapper application JSON mapper
	 */
	public OrderEventProducer(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Appends an order event to the stream.
	 *
	 * @param orderId business identifier of the order
	 * @param type order event category
	 * @param amount monetary amount associated with the event
	 * @param customer customer associated with the order
	 * @return the appended event including its generated stream entry ID
	 */
	public PublishOrderEventResponse publish(
			String orderId, OrderEventType type, BigDecimal amount, String customer) {
		validate(orderId, type, amount, customer);

		OrderEvent event = new OrderEvent(null, orderId, type, amount, customer, Instant.now());
		Map<String, String> fields = Map.of(
				"type", type.name(),
				"payload", serialize(event));

		RecordId recordId = redisTemplate.opsForStream().add(
				StreamRecords.mapBacked(fields).withStreamKey(StreamsConfig.STREAM_KEY));
		String entryId = recordId == null ? null : recordId.getValue();

		return new PublishOrderEventResponse(
				entryId, orderId, type, amount, customer, event.occurredAt());
	}

	private void validate(String orderId, OrderEventType type, BigDecimal amount, String customer) {
		if (orderId == null || orderId.isBlank()) {
			throw new IllegalArgumentException("Order ID must not be blank");
		}
		if (type == null) {
			throw new IllegalArgumentException("Order event type must not be null");
		}
		if (amount == null || amount.signum() <= 0) {
			throw new IllegalArgumentException("Amount must be greater than zero");
		}
		if (customer == null || customer.isBlank()) {
			throw new IllegalArgumentException("Customer must not be blank");
		}
	}

	private String serialize(OrderEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize order event", exception);
		}
	}
}
