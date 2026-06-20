/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

/**
 * API response containing the retained activity count for a user.
 *
 * @param userId identifier of the user who owns the feed
 * @param count number of retained activities
 */
public record ActivityCountResponse(String userId, long count) {
}
