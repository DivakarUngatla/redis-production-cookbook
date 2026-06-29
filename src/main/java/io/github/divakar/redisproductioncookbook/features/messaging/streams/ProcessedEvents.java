/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.streams;

import java.util.List;

/**
 * API view of the events a single consumer processed, used to make work-sharing across
 * the consumer group observable.
 *
 * @param consumer name of the consumer (e.g. {@code worker-1})
 * @param processed events it processed, newest first
 */
public record ProcessedEvents(String consumer, List<OrderEvent> processed) {
}
