/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hash;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for user-profile hash persistence and expiration behavior.
 *
 * <p>A Redis instance must be available using the connection configured for the
 * Spring Boot test context.</p>
 */
@SpringBootTest
class UserProfileRepositoryIT {

	private static final String USER_ID = "repository-it-user";

	@Autowired
	private UserProfileRepository repository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@AfterEach
	void cleanUp() {
		repository.deleteById(USER_ID);
	}

	@Test
	void savesReadsAndDeletesAUserProfileHash() {
		UserProfile profile = new UserProfile(USER_ID, "Ada Lovelace", "ada@example.com", 36);

		repository.save(profile);

		assertThat(repository.findById(USER_ID))
				.isPresent()
				.get()
				.satisfies(saved -> {
					assertThat(saved.getId()).isEqualTo(USER_ID);
					assertThat(saved.getName()).isEqualTo("Ada Lovelace");
					assertThat(saved.getEmail()).isEqualTo("ada@example.com");
					assertThat(saved.getAge()).isEqualTo(36);
				});

		assertThat(repository.deleteById(USER_ID)).isTrue();
		assertThat(repository.findById(USER_ID)).isEmpty();
	}

	@Test
	void savesAUserProfileWithTtl() {
		UserProfile profile = new UserProfile(USER_ID, "Ada Lovelace", "ada@example.com", 36);

		repository.save(profile, Duration.ofMinutes(5));

		Long ttlSeconds = redisTemplate.getExpire("user:profile:{" + USER_ID + "}");
		assertThat(ttlSeconds).isPositive().isLessThanOrEqualTo(300L);
	}

}
