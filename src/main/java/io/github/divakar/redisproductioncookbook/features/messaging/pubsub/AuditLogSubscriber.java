/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Subscriber representing the auditing concern: it records each received notification
 * for observability and metrics, independently of real-time delivery.
 *
 * <p>This is a best-effort, transient audit only. Because Pub/Sub is at-most-once,
 * an audit that must never miss an event belongs in a durable log such as Redis
 * Streams or Kafka, not here.</p>
 */
@Component
public class AuditLogSubscriber extends AbstractNotificationSubscriber {

	private static final Logger log = LoggerFactory.getLogger(AuditLogSubscriber.class);

	/**
	 * Creates the audit log subscriber with its own listener container.
	 *
	 * @param container listener container dedicated to this subscriber
	 * @param topic channel this subscriber listens on
	 * @param objectMapper application JSON mapper
	 */
	public AuditLogSubscriber(
			@Qualifier("auditListenerContainer") RedisMessageListenerContainer container,
			ChannelTopic topic,
			ObjectMapper objectMapper) {
		super(container, topic, objectMapper);
	}

	@Override
	public String name() {
		return "audit-log";
	}

	@Override
	protected void handle(Notification notification) {
		log.info("Audit: {} notification {} on {} at {}",
				notification.type(), notification.id(), notification.channel(),
				notification.publishedAt());
	}

}
