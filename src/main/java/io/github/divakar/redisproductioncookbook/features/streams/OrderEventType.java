/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.streams;

/**
 * Business events in an order's lifecycle that flow through the reliable stream pipeline.
 *
 * <p>These are durable, must-not-be-lost events — the opposite of the transient signals
 * the Pub/Sub module handles.</p>
 */
public enum OrderEventType {
	ORDER_PLACED,
	PAYMENT_RECEIVED,
	ORDER_SHIPPED,
	ORDER_CANCELLED
}
