/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.messaging.pubsub;

import java.util.List;

/**
 * API view of the notifications a single subscriber has received, used to make the
 * broadcast fan-out observable across subscribers.
 *
 * @param subscriber name of the subscriber
 * @param received notifications it received, newest first
 */
public record SubscriberNotifications(String subscriber, List<Notification> received) {
}
