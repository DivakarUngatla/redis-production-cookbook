/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Describes a score submitted through the leaderboard API.
 *
 * @param playerId unique player identifier
 * @param playerName player display name
 * @param score absolute score that replaces any existing score
 */
public record ScoreSubmissionRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "[a-zA-Z0-9_-]+", message = "must contain only letters, numbers, '_' or '-'")
		String playerId,
		@NotBlank @Size(max = 100) String playerName,
		@NotNull Double score) {

	/**
	 * Validates that the score can be represented by a Redis Sorted Set.
	 *
	 * @return {@code true} when the score is absent or finite
	 */
	@AssertTrue(message = "score must be finite")
	public boolean isScoreFinite() {
		return score == null || Double.isFinite(score);
	}

}
