/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

/**
 * API response containing a player's current score.
 *
 * @param playerId unique player identifier
 * @param score current leaderboard score
 */
public record LeaderboardScoreResponse(String playerId, double score) {
}
