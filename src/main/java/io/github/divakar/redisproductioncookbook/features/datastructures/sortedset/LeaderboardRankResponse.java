/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

/**
 * API response containing a player's one-based leaderboard position.
 *
 * @param playerId unique player identifier
 * @param rank one-based leaderboard position
 */
public record LeaderboardRankResponse(String playerId, long rank) {
}
