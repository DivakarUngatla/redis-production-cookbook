/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.pubsub;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for publishing notifications and inspecting the messages
 * this application instance has received over Redis Pub/Sub.
 */
@Validated
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

	private final NotificationPublisher publisher;
	private final List<AbstractNotificationSubscriber> subscribers;

	public NotificationController(
			NotificationPublisher publisher, List<AbstractNotificationSubscriber> subscribers) {
		this.publisher = publisher;
		this.subscribers = subscribers;
	}

	/**
	 * Publishes a notification to a Redis channel.
	 *
	 * @param request validated notification request
	 * @return the published notification with the number of subscribers notified
	 */
	@PostMapping("/publish")
	public PublishNotificationResponse publish(@Valid @RequestBody PublishNotificationRequest request) {
		return publisher.publish(request.channel(), request.type(), request.message());
	}

	/**
	 * Returns the notifications each subscriber has received, newest first. Because a
	 * published message is broadcast to every subscriber, all subscribers should report
	 * the same notifications.
	 *
	 * @param limit maximum number of notifications per subscriber, from 1 through 100
	 * @return received notifications grouped by subscriber
	 */
	@GetMapping("/received")
	public ResponseEntity<List<SubscriberNotifications>> getReceived(
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		List<SubscriberNotifications> views = subscribers.stream()
				.map(subscriber -> new SubscriberNotifications(
						subscriber.name(), subscriber.getRecent(limit)))
				.toList();
		return ResponseEntity.ok(views);
	}

}
