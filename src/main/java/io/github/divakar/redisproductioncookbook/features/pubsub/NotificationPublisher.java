/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.pubsub;

import java.time.Instant;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Publishes notifications to a Redis Pub/Sub channel.
 *
 * <p>Each notification is serialized to JSON and sent with {@code PUBLISH}. Redis
 * pushes it to every currently connected subscriber and returns how many received it;
 * a count of zero means no subscriber was listening and the message was lost.</p>
 */
@Service
public class NotificationPublisher {

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Creates the notification publisher.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param objectMapper application JSON mapper
	 */
	public NotificationPublisher(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Publishes a notification to the given channel.
	 *
	 * @param channel Redis channel to publish to
	 * @param type notification category
	 * @param message human-readable notification body
	 * @return the published notification with the number of subscribers notified
	 */
	public PublishNotificationResponse publish(String channel, NotificationType type, String message) {
		validate(channel, type, message);

		Notification notification = new Notification(
				newId(), channel, type, message, Instant.now());

		Long received = redisTemplate.convertAndSend(channel, serialize(notification));
		long subscribersNotified = received == null ? 0 : received;

		return new PublishNotificationResponse(
				notification.id(),
				notification.channel(),
				notification.type(),
				notification.message(),
				notification.publishedAt(),
				subscribersNotified);
	}

	private void validate(String channel, NotificationType type, String message) {
		if (channel == null || channel.isBlank()) {
			throw new IllegalArgumentException("Channel must not be blank");
		}
		if (type == null) {
			throw new IllegalArgumentException("Notification type must not be null");
		}
		if (message == null || message.isBlank()) {
			throw new IllegalArgumentException("Message must not be blank");
		}
	}

	private String serialize(Notification notification) {
		try {
			return objectMapper.writeValueAsString(notification);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize notification", exception);
		}
	}

	private String newId() {
		return UUID.randomUUID().toString().substring(0, 8);
	}

}
