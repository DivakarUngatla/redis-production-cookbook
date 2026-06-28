/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.pubsub;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Subscriber representing the real-time delivery concern: it would push each received
 * notification to the application instance's connected WebSocket/SSE clients.
 */
@Component
public class WebSocketPushSubscriber extends AbstractNotificationSubscriber {

	private static final Logger log = LoggerFactory.getLogger(WebSocketPushSubscriber.class);

	/**
	 * Creates the WebSocket push subscriber with its own listener container.
	 *
	 * @param container listener container dedicated to this subscriber
	 * @param topic channel this subscriber listens on
	 * @param objectMapper application JSON mapper
	 */
	public WebSocketPushSubscriber(
			@Qualifier("webSocketListenerContainer") RedisMessageListenerContainer container,
			ChannelTopic topic,
			ObjectMapper objectMapper) {
		super(container, topic, objectMapper);
	}

	@Override
	public String name() {
		return "websocket-push";
	}

	@Override
	protected void handle(Notification notification) {
		log.info("Pushing notification {} to connected WebSocket clients", notification.id());
	}

}
