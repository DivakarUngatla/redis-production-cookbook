/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.pubsub;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the Redis Pub/Sub notifications module.
 *
 * <p>Both subscribers register on startup, each on its own connection, so a single
 * publish should fan out to both. Delivery is asynchronous, so assertions poll the
 * subscribers' buffers with a bounded timeout. A Redis instance must be available on the
 * configured host and port.</p>
 *
 * <p>Note: Redis Pub/Sub is global to the instance and not scoped by database number, so
 * each test uses a unique message body to identify the notification it published.</p>
 */
@SpringBootTest
class NotificationPubSubIT {

	private final NotificationPublisher publisher;
	private final WebSocketPushSubscriber webSocketPushSubscriber;
	private final AuditLogSubscriber auditLogSubscriber;
	private final StringRedisTemplate redisTemplate;

	@Autowired
	NotificationPubSubIT(
			NotificationPublisher publisher,
			WebSocketPushSubscriber webSocketPushSubscriber,
			AuditLogSubscriber auditLogSubscriber,
			StringRedisTemplate redisTemplate) {
		this.publisher = publisher;
		this.webSocketPushSubscriber = webSocketPushSubscriber;
		this.auditLogSubscriber = auditLogSubscriber;
		this.redisTemplate = redisTemplate;
	}

	@Test
	void publishFansOutToBothSubscribers() {
		String marker = "presence-" + UUID.randomUUID();

		publisher.publish(
				PubSubConfig.NOTIFICATIONS_CHANNEL, NotificationType.PRESENCE_CHANGED, marker);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
			assertThat(receivedBy(webSocketPushSubscriber, marker)).isPresent();
			assertThat(receivedBy(auditLogSubscriber, marker)).isPresent();
		});

		Notification atWebSocket = receivedBy(webSocketPushSubscriber, marker).orElseThrow();
		Notification atAudit = receivedBy(auditLogSubscriber, marker).orElseThrow();

		assertThat(atWebSocket.id()).isEqualTo(atAudit.id());
		assertThat(atWebSocket.channel()).isEqualTo(PubSubConfig.NOTIFICATIONS_CHANNEL);
		assertThat(atWebSocket.type()).isEqualTo(NotificationType.PRESENCE_CHANGED);
		assertThat(atWebSocket.message()).isEqualTo(marker);
	}

	@Test
	void publishReportsBothSubscribersNotified() {
		String marker = "metric-" + UUID.randomUUID();

		PublishNotificationResponse response = publisher.publish(
				PubSubConfig.NOTIFICATIONS_CHANNEL, NotificationType.METRIC_UPDATE, marker);

		// Both subscribers from this app hold their own connection; extra subscribers
		// (e.g. a redis-cli) would only increase this, so assert "at least two".
		assertThat(response.subscribersNotified()).isGreaterThanOrEqualTo(2);
		assertThat(response.type()).isEqualTo(NotificationType.METRIC_UPDATE);
		assertThat(response.id()).isNotBlank();
	}

	@Test
	void publishToUnsubscribedChannelNotifiesNoSubscribers() {
		String channel = "notifications:no-subscribers-" + UUID.randomUUID();

		PublishNotificationResponse response = publisher.publish(
				channel, NotificationType.SYSTEM_ALERT, "into the void");

		assertThat(response.subscribersNotified()).isZero();
	}

	@Test
	void nonJsonMessageIsStoredAsRawText() {
		String marker = "raw-" + UUID.randomUUID();

		// A raw PUBLISH (e.g. from redis-cli) is not JSON; subscribers must tolerate it.
		redisTemplate.convertAndSend(PubSubConfig.NOTIFICATIONS_CHANNEL, marker);

		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(receivedBy(webSocketPushSubscriber, marker)).isPresent());

		Notification raw = receivedBy(webSocketPushSubscriber, marker).orElseThrow();
		assertThat(raw.type()).isNull();
		assertThat(raw.message()).isEqualTo(marker);
		assertThat(raw.channel()).isEqualTo(PubSubConfig.NOTIFICATIONS_CHANNEL);
	}

	private static Optional<Notification> receivedBy(
			AbstractNotificationSubscriber subscriber, String message) {
		return subscriber.getRecent(AbstractNotificationSubscriber.MAX_RECEIVED).stream()
				.filter(notification -> message.equals(notification.message()))
				.findFirst();
	}

}
