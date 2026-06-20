/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.sortedset;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the Redis-backed global leaderboard repository.
 *
 * <p>The tests use Redis database 15 and clear only the leaderboard keys owned by
 * this test fixture. A Redis instance must be available on the configured host and
 * port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=15")
class LeaderboardRepositoryIT {

	private static final String ADA = "leaderboard-it-ada";
	private static final String GRACE = "leaderboard-it-grace";
	private static final String LINUS = "leaderboard-it-linus";

	private final LeaderboardRepository repository;
	private final RedisTemplate<String, String> redisTemplate;

	@Autowired
	LeaderboardRepositoryIT(
			LeaderboardRepository repository,
			@Qualifier("leaderboardRedisTemplate") RedisTemplate<String, String> redisTemplate) {
		this.repository = repository;
		this.redisTemplate = redisTemplate;
	}

	@BeforeEach
	@AfterEach
	void cleanUp() {
		redisTemplate.delete(LeaderboardRepository.LEADERBOARD_KEY);
		redisTemplate.delete(List.of(
				profileKey(ADA),
				profileKey(GRACE),
				profileKey(LINUS)));
	}

	@Test
	void addsAndUpdatesAPlayerScore() {
		repository.addScore(ADA, "Ada Lovelace", 100);

		assertThat(repository.getScore(ADA)).contains(100.0);

		repository.addScore(ADA, "Ada Byron", 175);

		assertThat(repository.getScore(ADA)).contains(175.0);
		assertThat(repository.getTopPlayers(1))
				.containsExactly(new LeaderboardEntry(ADA, "Ada Byron", 175));
	}

	@Test
	void returnsScoresAndOneBasedRanks() {
		repository.addScore(ADA, "Ada Lovelace", 100);
		repository.addScore(GRACE, "Grace Hopper", 300);
		repository.addScore(LINUS, "Linus Torvalds", 200);

		assertThat(repository.getScore(LINUS)).contains(200.0);
		assertThat(repository.getRank(GRACE)).contains(1L);
		assertThat(repository.getRank(LINUS)).contains(2L);
		assertThat(repository.getRank(ADA)).contains(3L);
		assertThat(repository.getRank("missing-player")).isEmpty();
	}

	@Test
	void returnsTopPlayersInDescendingScoreOrder() {
		repository.addScore(ADA, "Ada Lovelace", 100);
		repository.addScore(GRACE, "Grace Hopper", 300);
		repository.addScore(LINUS, "Linus Torvalds", 200);

		assertThat(repository.getTopPlayers(2)).containsExactly(
				new LeaderboardEntry(GRACE, "Grace Hopper", 300),
				new LeaderboardEntry(LINUS, "Linus Torvalds", 200));
	}

	@Test
	void removesPlayerAndProfileMetadata() {
		repository.addScore(ADA, "Ada Lovelace", 100);

		assertThat(repository.removePlayer(ADA)).isTrue();
		assertThat(repository.getScore(ADA)).isEmpty();
		assertThat(repository.getRank(ADA)).isEmpty();
		assertThat(redisTemplate.hasKey(profileKey(ADA))).isFalse();
		assertThat(repository.removePlayer(ADA)).isFalse();
	}

	private String profileKey(String playerId) {
		return LeaderboardRepository.PLAYER_PROFILE_PREFIX + "{" + playerId + "}";
	}

}
