/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StreamOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for the reliable order-event stream: appending events,
 * inspecting recent entries, observing which consumer processed what, and viewing the
 * consumer group's pending (unacknowledged) work.
 */
@Validated
@RestController
@RequestMapping("/api/order-events")
public class OrderEventController {

	private static final int PENDING_SCAN = 1000;

	private final OrderEventProducer producer;
	private final OrderEventProcessor processor;
	private final StringRedisTemplate redisTemplate;

	public OrderEventController(
			OrderEventProducer producer,
			OrderEventProcessor processor,
			StringRedisTemplate redisTemplate) {
		this.producer = producer;
		this.processor = processor;
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Appends an order event to the stream.
	 *
	 * @param request validated order event request
	 * @return the appended event with its generated stream entry ID
	 */
	@PostMapping
	public PublishOrderEventResponse publish(@Valid @RequestBody PublishOrderEventRequest request) {
		return producer.publish(
				request.orderId(), request.type(), request.amount(), request.customer());
	}

	/**
	 * Returns the events processed by each consumer, newest first. Since the group shares
	 * the work, each event appears under exactly one consumer.
	 *
	 * @param limit maximum number of events per consumer, from 1 through 100
	 * @return processed events grouped by consumer
	 */
	@GetMapping("/processed")
	public ResponseEntity<List<ProcessedEvents>> getProcessed(
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return ResponseEntity.ok(processor.processedByConsumer(limit));
	}

	/**
	 * Returns the most recent entries in the stream, newest first.
	 *
	 * @param limit maximum number of entries to return, from 1 through 100
	 * @return recent order events
	 */
	@GetMapping("/recent")
	public ResponseEntity<List<OrderEvent>> getRecent(
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		List<MapRecord<String, Object, Object>> records =
				redisTemplate.opsForStream().reverseRange(StreamsConfig.STREAM_KEY, Range.unbounded());
		if (records == null) {
			return ResponseEntity.ok(List.of());
		}
		List<OrderEvent> events = records.stream()
				.limit(limit)
				.map(record -> processor.toOrderEvent(
						record.getId().getValue(),
						stringField(record, "type"),
						stringField(record, "payload")))
				.toList();
		return ResponseEntity.ok(events);
	}

	/**
	 * Returns a summary of the consumer group's pending (delivered-but-unacknowledged)
	 * entries.
	 *
	 * @return pending summary for the group
	 */
	@GetMapping("/pending")
	public ResponseEntity<PendingSummaryResponse> getPending() {
		StreamOperations<String, Object, Object> streamOps = redisTemplate.opsForStream();
		PendingMessages pending = streamOps.pending(
				StreamsConfig.STREAM_KEY, StreamsConfig.GROUP, Range.unbounded(), PENDING_SCAN);

		Map<String, Long> perConsumer = new TreeMap<>();
		String minId = null;
		String maxId = null;
		for (PendingMessage message : pending) {
			String id = message.getId().getValue();
			if (minId == null) {
				minId = id;
			}
			maxId = id;
			perConsumer.merge(message.getConsumerName(), 1L, Long::sum);
		}

		return ResponseEntity.ok(new PendingSummaryResponse(
				StreamsConfig.GROUP, pending.size(), minId, maxId, perConsumer));
	}

	private static String stringField(MapRecord<String, Object, Object> record, String field) {
		Object value = record.getValue().get(field);
		return value == null ? null : value.toString();
	}
}
