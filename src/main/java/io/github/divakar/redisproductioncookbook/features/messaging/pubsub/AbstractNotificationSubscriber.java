/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Base for notification subscribers that receive messages over Redis Pub/Sub and
 * retain the most recent ones in a bounded in-memory buffer for observability.
 *
 * <p>Each concrete subscriber is given its <strong>own</strong>
 * {@link RedisMessageListenerContainer}, so it holds a separate Redis connection and
 * is counted as a distinct subscriber by Redis ({@code PUBSUB NUMSUB}). A single
 * publish therefore fans out to every subscriber independently, demonstrating the
 * broadcast nature of Pub/Sub.</p>
 */
public abstract class AbstractNotificationSubscriber implements MessageListener {

	static final int MAX_RECEIVED = 100;

	private final Logger log = LoggerFactory.getLogger(getClass());

	private final RedisMessageListenerContainer container;
	private final ChannelTopic topic;
	private final ObjectMapper objectMapper;

	private final Deque<Notification> received = new ArrayDeque<>();

	/**
	 * Creates a subscriber bound to its own listener container.
	 *
	 * @param container listener container dedicated to this subscriber
	 * @param topic channel this subscriber listens on
	 * @param objectMapper application JSON mapper
	 */
	protected AbstractNotificationSubscriber(
			RedisMessageListenerContainer container,
			ChannelTopic topic,
			ObjectMapper objectMapper) {
		this.container = container;
		this.topic = topic;
		this.objectMapper = objectMapper;
	}

	/**
	 * A stable name identifying this subscriber's concern.
	 *
	 * @return the subscriber name
	 */
	public abstract String name();

	/**
	 * Handles a received notification in a concern-specific way.
	 *
	 * @param notification the received notification
	 */
	protected abstract void handle(Notification notification);

	@PostConstruct
	void subscribe() {
		container.addMessageListener(this, topic);
		log.info("[{}] subscribed to Redis channel {}", name(), topic.getTopic());
	}

	@Override
	public void onMessage(Message message, byte[] pattern) {
		String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
		String body = new String(message.getBody(), StandardCharsets.UTF_8);
		Notification notification = parse(channel, body);
		record(notification);
		try {
			handle(notification);
		}
		catch (Exception exception) {
			log.warn("[{}] handler failed for notification {}: {}",
					name(), notification.id(), exception.getMessage());
		}
	}

	/**
	 * Returns the most recently received notifications, newest first.
	 *
	 * @param limit maximum number of notifications to return, from 1 through 100
	 * @return received notifications in newest-first order
	 */
	public List<Notification> getRecent(int limit) {
		if (limit < 1 || limit > MAX_RECEIVED) {
			throw new IllegalArgumentException("Limit must be between 1 and 100");
		}
		synchronized (received) {
			return received.stream().limit(limit).toList();
		}
	}

	private void record(Notification notification) {
		synchronized (received) {
			received.addFirst(notification);
			while (received.size() > MAX_RECEIVED) {
				received.removeLast();
			}
		}
	}

	private Notification parse(String channel, String body) {
		try {
			Notification parsed = objectMapper.readValue(body, Notification.class);
			return new Notification(
					parsed.id() != null ? parsed.id() : newId(),
					parsed.channel() != null ? parsed.channel() : channel,
					parsed.type(),
					parsed.message(),
					parsed.publishedAt() != null ? parsed.publishedAt() : Instant.now());
		}
		catch (Exception exception) {
			// Tolerate non-JSON payloads (e.g. a raw redis-cli PUBLISH) so they remain observable.
			log.warn("[{}] received non-JSON message on {}, storing as raw text", name(), channel);
			return new Notification(newId(), channel, null, body, Instant.now());
		}
	}

	private String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
