/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the Redis Streams order-event pipeline.
 *
 * <p>The consumer group and its workers run for the whole Spring context, processing
 * entries asynchronously, so assertions poll the processor's buffers with a bounded
 * timeout. The tests run against a dedicated Redis database so the stream and consumer
 * group are isolated from the other features' contexts. Each test also tags events with
 * unique order IDs. A Redis instance must be available on the configured host and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=11")
class OrderEventStreamIT {

	private final OrderEventProducer producer;
	private final OrderEventProcessor processor;
	private final StringRedisTemplate redisTemplate;

	@Autowired
	OrderEventStreamIT(
			OrderEventProducer producer,
			OrderEventProcessor processor,
			StringRedisTemplate redisTemplate) {
		this.producer = producer;
		this.processor = processor;
		this.redisTemplate = redisTemplate;
	}

	@Test
	void publishedEventIsProcessedExactlyOnceAndAcknowledged() {
		String orderId = "order-" + UUID.randomUUID();

		PublishOrderEventResponse response = producer.publish(
				orderId, OrderEventType.ORDER_PLACED, new BigDecimal("42.50"), "alice");

		assertThat(response.entryId()).isNotBlank();

		await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
				assertThat(processedMatching(Set.of(orderId))).hasSize(1));

		OrderEvent processed = processedMatching(Set.of(orderId)).get(0);
		assertThat(processed.entryId()).isEqualTo(response.entryId());
		assertThat(processed.type()).isEqualTo(OrderEventType.ORDER_PLACED);
		assertThat(processed.amount()).isEqualByComparingTo("42.50");
		assertThat(processed.customer()).isEqualTo("alice");

		// Acknowledged entries must not remain in the consumer group's pending list.
		assertThat(pendingEntryIds()).doesNotContain(response.entryId());
	}

	@Test
	void manyEventsAreEachProcessedExactlyOnceAcrossTheGroup() {
		Set<String> orderIds = new HashSet<>();
		OrderEventType[] types = OrderEventType.values();
		for (int i = 0; i < 20; i++) {
			String orderId = "order-" + UUID.randomUUID();
			orderIds.add(orderId);
			producer.publish(
					orderId, types[i % types.length], new BigDecimal("10.00"), "customer-" + i);
		}

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
				assertThat(processedMatching(orderIds)).hasSize(orderIds.size()));

		List<OrderEvent> processed = processedMatching(orderIds);
		// Exactly once: no order ID is processed more than one time across all consumers.
		long distinctOrderIds = processed.stream().map(OrderEvent::orderId).distinct().count();
		assertThat(distinctOrderIds).isEqualTo(orderIds.size());
	}

	private List<OrderEvent> processedMatching(Set<String> orderIds) {
		return processor.processedByConsumer(OrderEventProcessor.MAX_PROCESSED).stream()
				.flatMap(view -> view.processed().stream())
				.filter(event -> orderIds.contains(event.orderId()))
				.collect(Collectors.toList());
	}

	private Set<String> pendingEntryIds() {
		PendingMessages pending = redisTemplate.opsForStream().pending(
				StreamsConfig.STREAM_KEY, StreamsConfig.GROUP, Range.unbounded(), 1000);
		Set<String> ids = new HashSet<>();
		for (PendingMessage message : pending) {
			ids.add(message.getId().getValue());
		}
		return ids;
	}
}
