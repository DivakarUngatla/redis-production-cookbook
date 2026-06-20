/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

/**
 * Represents a ranked player returned by the global leaderboard.
 *
 * @param playerId unique player identifier
 * @param playerName display name stored in the player profile
 * @param score current leaderboard score
 */
public record LeaderboardEntry(String playerId, String playerName, double score) {
}
