/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for submitting scores and querying the global leaderboard.
 */
@Validated
@RestController
@RequestMapping("/api/leaderboards")
public class LeaderboardController {

	private final LeaderboardRepository repository;

	public LeaderboardController(LeaderboardRepository repository) {
		this.repository = repository;
	}

	/**
	 * Creates or replaces a player's global leaderboard score.
	 *
	 * @param request validated score submission
	 * @return the stored leaderboard entry
	 */
	@PostMapping("/global/scores")
	public ResponseEntity<LeaderboardEntry> addScore(@Valid @RequestBody ScoreSubmissionRequest request) {
		LeaderboardEntry entry = repository.addScore(
				request.playerId(), request.playerName(), request.score());
		URI location = URI.create("/api/leaderboards/global/score/" + request.playerId());
		return ResponseEntity.created(location).body(entry);
	}

	/**
	 * Finds a player's one-based global rank.
	 *
	 * @param playerId unique player identifier
	 * @return the rank or HTTP 404 when the player is not ranked
	 */
	@GetMapping("/global/rank/{playerId}")
	public ResponseEntity<LeaderboardRankResponse> getRank(
			@PathVariable @Pattern(regexp = "[a-zA-Z0-9_-]+") String playerId) {
		return repository.getRank(playerId)
				.map(rank -> ResponseEntity.ok(new LeaderboardRankResponse(playerId, rank)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Finds a player's current global score.
	 *
	 * @param playerId unique player identifier
	 * @return the score or HTTP 404 when the player is not ranked
	 */
	@GetMapping("/global/score/{playerId}")
	public ResponseEntity<LeaderboardScoreResponse> getScore(
			@PathVariable @Pattern(regexp = "[a-zA-Z0-9_-]+") String playerId) {
		return repository.getScore(playerId)
				.map(score -> ResponseEntity.ok(new LeaderboardScoreResponse(playerId, score)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Returns the highest-scoring global players.
	 *
	 * @param limit maximum result count, from 1 through 100
	 * @return ordered leaderboard entries
	 */
	@GetMapping("/global/top")
	public List<LeaderboardEntry> getTopPlayers(
			@RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
		return repository.getTopPlayers(limit);
	}

	/**
	 * Removes a player and associated profile metadata from the global leaderboard.
	 *
	 * @param playerId unique player identifier
	 * @return HTTP 204 when removed, or HTTP 404 when the player was not ranked
	 */
	@DeleteMapping("/global/player/{playerId}")
	public ResponseEntity<Void> removePlayer(
			@PathVariable @Pattern(regexp = "[a-zA-Z0-9_-]+") String playerId) {
		return repository.removePlayer(playerId)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

}
