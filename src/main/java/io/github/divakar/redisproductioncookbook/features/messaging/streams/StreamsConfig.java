/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.stream.StreamMessageListenerContainer;
import org.springframework.data.redis.stream.StreamMessageListenerContainer.StreamMessageListenerContainerOptions;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires the Redis Streams infrastructure for the order-event pipeline.
 *
 * <p>On startup it ensures the {@code order-processors} consumer group exists on the
 * {@code orders:events} stream (creating the stream with {@code MKSTREAM} if needed), then
 * starts a {@link StreamMessageListenerContainer} with two consumers in that group. Each
 * consumer reads new messages ({@code >}) with manual acknowledgement, so an unacknowledged
 * entry stays in the Pending Entries List until processed.</p>
 *
 * <p>{@link EnableScheduling} powers the periodic recovery of stuck pending entries in
 * {@link PendingEventRecovery}.</p>
 */
@Configuration
@EnableScheduling
public class StreamsConfig {

	/** The append-only stream of order events. */
	public static final String STREAM_KEY = "orders:events";

	/** The single consumer group whose members share the work. */
	public static final String GROUP = "order-processors";

	/** First competing consumer in the group. */
	public static final String WORKER_1 = "worker-1";

	/** Second competing consumer in the group. */
	public static final String WORKER_2 = "worker-2";

	private static final Logger log = LoggerFactory.getLogger(StreamsConfig.class);

	/**
	 * Starts the listener container with two consumers sharing the stream.
	 *
	 * <p>The consumer group is created before the container starts so the workers can read
	 * with {@link ReadOffset#lastConsumed()} ({@code >}). Each delivered entry enters the
	 * group's Pending Entries List and is removed only when {@link OrderEventProcessor}
	 * acknowledges it.</p>
	 *
	 * @param connectionFactory configured Redis connection factory
	 * @param redisTemplate string-serialized Redis template used to create the group
	 * @param processor shared processor the workers delegate to
	 * @return a started stream message listener container
	 */
	@Bean
	StreamMessageListenerContainer<String, MapRecord<String, String, String>> streamListenerContainer(
			RedisConnectionFactory connectionFactory,
			StringRedisTemplate redisTemplate,
			OrderEventProcessor processor) {

		ensureGroup(redisTemplate);

		StreamMessageListenerContainerOptions<String, MapRecord<String, String, String>> options =
				StreamMessageListenerContainerOptions.builder()
						.pollTimeout(Duration.ofSeconds(2))
						.batchSize(10)
						.errorHandler(throwable -> log.warn(
								"Listener error (entry left unacknowledged for recovery): {}",
								throwable.getMessage()))
						.build();

		StreamMessageListenerContainer<String, MapRecord<String, String, String>> container =
				StreamMessageListenerContainer.create(connectionFactory, options);

		registerWorker(container, processor, WORKER_1);
		registerWorker(container, processor, WORKER_2);

		container.start();
		log.info("Started consumers {} and {} on group {} ({})",
				WORKER_1, WORKER_2, GROUP, STREAM_KEY);
		return container;
	}

	private void registerWorker(
			StreamMessageListenerContainer<String, MapRecord<String, String, String>> container,
			OrderEventProcessor processor,
			String worker) {
		container.receive(
				Consumer.from(GROUP, worker),
				StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()),
				record -> processor.process(
						worker,
						record.getId(),
						record.getValue().get("type"),
						record.getValue().get("payload"),
						"true".equals(record.getValue().get("failFirstAttempt"))));
	}

	private void ensureGroup(StringRedisTemplate redisTemplate) {
		try {
			redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), GROUP);
			log.info("Created consumer group {} on {}", GROUP, STREAM_KEY);
		}
		catch (Exception exception) {
			// Group already exists (BUSYGROUP) — expected on every restart after the first.
			log.info("Consumer group {} already present on {}", GROUP, STREAM_KEY);
		}
	}
}
