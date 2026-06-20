/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Repository;

/**
 * Stores the global leaderboard in a Redis Sorted Set and player display metadata
 * in separate Redis hashes.
 *
 * <p>The Sorted Set member is the player ID and its score controls ordering. Player
 * names are deliberately kept outside the member value so metadata can change
 * without rewriting leaderboard membership.</p>
 */
@Repository
public class LeaderboardRepository {

	static final String LEADERBOARD_KEY = "leaderboard:global";
	static final String PLAYER_PROFILE_PREFIX = "player:profile:";

	private static final String PLAYER_ID_FIELD = "playerId";
	private static final String PLAYER_NAME_FIELD = "playerName";

	private final RedisTemplate<String, String> redisTemplate;
	private final ZSetOperations<String, String> sortedSets;
	private final HashOperations<String, String, String> hashes;

	public LeaderboardRepository(
			@Qualifier("leaderboardRedisTemplate") RedisTemplate<String, String> redisTemplate) {
		this.redisTemplate = redisTemplate;
		this.sortedSets = redisTemplate.opsForZSet();
		this.hashes = redisTemplate.opsForHash();
	}

	/**
	 * Adds a player to the global leaderboard or replaces the player's existing score.
	 *
	 * @param playerId unique player identifier
	 * @param playerName player display name
	 * @param score absolute leaderboard score
	 * @return the stored leaderboard entry
	 */
	public LeaderboardEntry addScore(String playerId, String playerName, double score) {
		validatePlayer(playerId, playerName);
		if (!Double.isFinite(score)) {
			throw new IllegalArgumentException("Score must be finite");
		}

		hashes.putAll(profileKey(playerId), Map.of(
				PLAYER_ID_FIELD, playerId,
				PLAYER_NAME_FIELD, playerName));
		sortedSets.add(LEADERBOARD_KEY, playerId, score);
		return new LeaderboardEntry(playerId, playerName, score);
	}

	/**
	 * Returns a player's current score.
	 *
	 * @param playerId unique player identifier
	 * @return the score, or an empty value when the player is not ranked
	 */
	public Optional<Double> getScore(String playerId) {
		return Optional.ofNullable(sortedSets.score(LEADERBOARD_KEY, playerId));
	}

	/**
	 * Returns a player's one-based rank, ordered from highest to lowest score.
	 *
	 * @param playerId unique player identifier
	 * @return the one-based rank, or an empty value when the player is not ranked
	 */
	public Optional<Long> getRank(String playerId) {
		return Optional.ofNullable(sortedSets.reverseRank(LEADERBOARD_KEY, playerId))
				.map(zeroBasedRank -> zeroBasedRank + 1);
	}

	/**
	 * Returns the highest-scoring players in descending score order.
	 *
	 * @param limit maximum number of players to return
	 * @return ordered leaderboard entries
	 */
	public List<LeaderboardEntry> getTopPlayers(int limit) {
		if (limit < 1) {
			throw new IllegalArgumentException("Limit must be greater than zero");
		}

		Set<ZSetOperations.TypedTuple<String>> players =
				sortedSets.reverseRangeWithScores(LEADERBOARD_KEY, 0, limit - 1L);
		if (players == null || players.isEmpty()) {
			return List.of();
		}

		return players.stream()
				.map(this::toLeaderboardEntry)
				.toList();
	}

	/**
	 * Removes a player from the leaderboard and deletes the associated profile hash.
	 *
	 * @param playerId unique player identifier
	 * @return {@code true} when a ranked player was removed
	 */
	public boolean removePlayer(String playerId) {
		Long removed = sortedSets.remove(LEADERBOARD_KEY, playerId);
		redisTemplate.delete(profileKey(playerId));
		return removed != null && removed > 0;
	}

	private LeaderboardEntry toLeaderboardEntry(ZSetOperations.TypedTuple<String> player) {
		String playerId = Objects.requireNonNull(player.getValue(), "Leaderboard member must not be null");
		Double score = Objects.requireNonNull(player.getScore(), "Leaderboard score must not be null");
		String playerName = hashes.get(profileKey(playerId), PLAYER_NAME_FIELD);
		if (playerName == null) {
			throw new IllegalStateException("Missing profile metadata for player " + playerId);
		}
		return new LeaderboardEntry(playerId, playerName, score);
	}

	private void validatePlayer(String playerId, String playerName) {
		if (playerId == null || playerId.isBlank()) {
			throw new IllegalArgumentException("Player ID must not be blank");
		}
		if (playerName == null || playerName.isBlank()) {
			throw new IllegalArgumentException("Player name must not be blank");
		}
	}

	private String profileKey(String playerId) {
		return PLAYER_PROFILE_PREFIX + "{" + playerId + "}";
	}

}
