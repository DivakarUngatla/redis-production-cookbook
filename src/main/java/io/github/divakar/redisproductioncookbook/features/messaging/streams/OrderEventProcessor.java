/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Shared processing for order events consumed from the stream.
 *
 * <p>Both the normal workers and the recovery path delegate here. Processing parses the
 * entry payload, records it in a bounded per-consumer buffer (so work-sharing is
 * observable through the API), and acknowledges the entry with {@code XACK} so it leaves
 * the consumer group's Pending Entries List.</p>
 *
 * <p>Acknowledgement happens only after the work succeeds. If processing throws before the
 * ack, the entry remains pending and is later reclaimed by the recovery path — the
 * at-least-once contract. Handlers must therefore be idempotent.</p>
 */
@Component
public class OrderEventProcessor {

	static final int MAX_PROCESSED = 100;

	private static final Logger log = LoggerFactory.getLogger(OrderEventProcessor.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	private final ConcurrentMap<String, Deque<OrderEvent>> processedByConsumer =
			new ConcurrentHashMap<>();

	/** Entry IDs for which the one-time simulated failure has already been injected. */
	private final Set<String> failureInjected = ConcurrentHashMap.newKeySet();

	/**
	 * Creates the order event processor.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param objectMapper application JSON mapper
	 */
	public OrderEventProcessor(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Processes a single stream entry on behalf of the given consumer: parse, record, then
	 * acknowledge.
	 *
	 * @param consumer name of the consumer handling the entry
	 * @param entryId stream entry ID
	 * @param type raw {@code type} field of the entry
	 * @param payload raw JSON {@code payload} field of the entry
	 * @param failFirstAttempt when {@code true}, throw before acknowledging the very first
	 *     time this entry is seen, simulating a consumer crash mid-processing; the entry
	 *     stays in the Pending Entries List and is later reclaimed by recovery (demo only)
	 */
	public void process(
			String consumer, RecordId entryId, String type, String payload, boolean failFirstAttempt) {
		if (failFirstAttempt && failureInjected.add(entryId.getValue())) {
			log.warn("[{}] simulated crash before ACK for entry {} — left in the PEL for recovery",
					consumer, entryId.getValue());
			throw new IllegalStateException(
					"Simulated processing failure before ACK for entry " + entryId.getValue());
		}
		OrderEvent event = toOrderEvent(entryId.getValue(), type, payload);
		record(consumer, event);
		redisTemplate.opsForStream()
				.acknowledge(StreamsConfig.STREAM_KEY, StreamsConfig.GROUP, entryId.getValue());
		log.info("[{}] processed and acked {} for order {} (entry {})",
				consumer, event.type(), event.orderId(), entryId.getValue());
	}

	/**
	 * Returns the events processed by each consumer, newest first, ordered by consumer name.
	 *
	 * @param limit maximum number of events per consumer, from 1 through 100
	 * @return processed events grouped by consumer
	 */
	public List<ProcessedEvents> processedByConsumer(int limit) {
		if (limit < 1 || limit > MAX_PROCESSED) {
			throw new IllegalArgumentException("Limit must be between 1 and 100");
		}
		List<ProcessedEvents> views = new ArrayList<>();
		processedByConsumer.forEach((consumer, events) -> {
			synchronized (events) {
				views.add(new ProcessedEvents(consumer, events.stream().limit(limit).toList()));
			}
		});
		views.sort(Comparator.comparing(ProcessedEvents::consumer));
		return views;
	}

	private void record(String consumer, OrderEvent event) {
		Deque<OrderEvent> events =
				processedByConsumer.computeIfAbsent(consumer, key -> new ArrayDeque<>());
		synchronized (events) {
			events.addFirst(event);
			while (events.size() > MAX_PROCESSED) {
				events.removeLast();
			}
		}
	}

	/**
	 * Deserializes a stream entry's fields into an {@link OrderEvent} without acknowledging
	 * it. Used by read-only inspection endpoints.
	 *
	 * @param entryId stream entry ID
	 * @param type raw {@code type} field of the entry
	 * @param payload raw JSON {@code payload} field of the entry
	 * @return the deserialized order event
	 */
	public OrderEvent toOrderEvent(String entryId, String type, String payload) {
		OrderEventType eventType = type == null ? null : OrderEventType.valueOf(type);
		if (payload == null) {
			return new OrderEvent(entryId, null, eventType, null, null, null);
		}
		try {
			OrderEvent parsed = objectMapper.readValue(payload, OrderEvent.class);
			return new OrderEvent(
					entryId,
					parsed.orderId(),
					eventType != null ? eventType : parsed.type(),
					parsed.amount(),
					parsed.customer(),
					parsed.occurredAt());
		}
		catch (Exception exception) {
			throw new IllegalStateException(
					"Could not parse order event payload for entry " + entryId, exception);
		}
	}
}
