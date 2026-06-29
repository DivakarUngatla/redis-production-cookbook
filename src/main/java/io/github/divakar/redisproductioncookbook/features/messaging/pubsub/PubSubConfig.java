/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Wires the Redis Pub/Sub infrastructure for the notifications module.
 *
 * <p>Publishing reuses the auto-configured {@code StringRedisTemplate}. Subscribing
 * requires a {@link RedisMessageListenerContainer}, which Spring Boot does not
 * auto-configure. Each subscriber is given its own container so it holds a separate
 * Redis connection and is counted as a distinct subscriber ({@code PUBSUB NUMSUB}),
 * making the broadcast fan-out of Pub/Sub observable.</p>
 */
@Configuration
public class PubSubConfig {

	/** Channel this module publishes notifications to and subscribes to on startup. */
	public static final String NOTIFICATIONS_CHANNEL = "notifications:realtime";

	/**
	 * The channel both subscribers listen on.
	 *
	 * @return the notifications channel topic
	 */
	@Bean
	ChannelTopic notificationsChannelTopic() {
		return new ChannelTopic(NOTIFICATIONS_CHANNEL);
	}

	/**
	 * Dedicated container (and connection) for the WebSocket push subscriber.
	 *
	 * @param connectionFactory configured Redis connection factory
	 * @return a started message listener container
	 */
	@Bean
	RedisMessageListenerContainer webSocketListenerContainer(
			RedisConnectionFactory connectionFactory) {
		return newContainer(connectionFactory);
	}

	/**
	 * Dedicated container (and connection) for the audit log subscriber.
	 *
	 * @param connectionFactory configured Redis connection factory
	 * @return a started message listener container
	 */
	@Bean
	RedisMessageListenerContainer auditListenerContainer(
			RedisConnectionFactory connectionFactory) {
		return newContainer(connectionFactory);
	}

	private static RedisMessageListenerContainer newContainer(
			RedisConnectionFactory connectionFactory) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(connectionFactory);
		return container;
	}

}
