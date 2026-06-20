/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for JSON session storage, renewal, and Redis expiration.
 *
 * <p>The tests use Redis database 14. A Redis instance must be available on the
 * connection configured for the Spring Boot test context.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=14")
class UserSessionRepositoryIT {

	private final UserSessionRepository repository;
	private final List<String> sessionIds = new ArrayList<>();

	@Autowired
	UserSessionRepositoryIT(UserSessionRepository repository) {
		this.repository = repository;
	}

	@AfterEach
	void cleanUp() {
		sessionIds.forEach(repository::deleteSession);
	}

	@Test
	void createsAndRetrievesSessionWithTtl() {
		UserSession created = createSession("user-123", Duration.ofMinutes(5));

		assertThat(repository.sessionExists(created.sessionId())).isTrue();
		assertThat(repository.getSession(created.sessionId())).contains(created);
		assertThat(repository.getRemainingTtl(created.sessionId()))
				.isPresent()
				.get()
				.satisfies(ttl -> {
					assertThat(ttl).isPositive();
					assertThat(ttl).isLessThanOrEqualTo(Duration.ofMinutes(5));
				});
	}

	@Test
	void extendsSessionAndPreservesCreationTime() {
		UserSession created = createSession("user-456", Duration.ofSeconds(5));

		UserSession extended = repository.extendSession(created.sessionId(), Duration.ofMinutes(10))
				.orElseThrow();

		assertThat(extended.sessionId()).isEqualTo(created.sessionId());
		assertThat(extended.userId()).isEqualTo(created.userId());
		assertThat(extended.createdAt()).isEqualTo(created.createdAt());
		assertThat(extended.expiresAt()).isAfter(created.expiresAt());
		assertThat(repository.getSession(created.sessionId())).contains(extended);
		assertThat(repository.getRemainingTtl(created.sessionId()))
				.hasValueSatisfying(ttl -> assertThat(ttl).isGreaterThan(Duration.ofMinutes(9)));
	}

	@Test
	void deletesSession() {
		UserSession created = createSession("user-789", Duration.ofMinutes(5));

		assertThat(repository.deleteSession(created.sessionId())).isTrue();
		assertThat(repository.sessionExists(created.sessionId())).isFalse();
		assertThat(repository.getSession(created.sessionId())).isEmpty();
		assertThat(repository.getRemainingTtl(created.sessionId())).isEmpty();
		assertThat(repository.deleteSession(created.sessionId())).isFalse();
	}

	@Test
	void expiresSessionAutomatically() throws InterruptedException {
		UserSession created = createSession("short-lived-user", Duration.ofSeconds(1));

		waitUntilExpired(created.sessionId(), Duration.ofSeconds(5));

		assertThat(repository.sessionExists(created.sessionId())).isFalse();
		assertThat(repository.getSession(created.sessionId())).isEmpty();
		assertThat(repository.getRemainingTtl(created.sessionId())).isEmpty();
	}

	private UserSession createSession(String userId, Duration ttl) {
		UserSession session = repository.createSession(userId, ttl);
		sessionIds.add(session.sessionId());
		return session;
	}

	private void waitUntilExpired(String sessionId, Duration timeout) throws InterruptedException {
		long deadline = System.nanoTime() + timeout.toNanos();
		while (repository.sessionExists(sessionId) && System.nanoTime() < deadline) {
			Thread.sleep(50);
		}
	}

}
