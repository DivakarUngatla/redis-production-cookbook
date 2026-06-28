/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.pubsub;

/**
 * Supported categories of real-time notifications broadcast over Redis Pub/Sub.
 */
public enum NotificationType {

	PRESENCE_CHANGED,
	TYPING_INDICATOR,
	METRIC_UPDATE,
	CACHE_INVALIDATION,
	SYSTEM_ALERT

}
