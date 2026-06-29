/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for the rate-limiting algorithms.
 *
 * <p>Runs against a dedicated Redis database with a unique client key per test, so cases cannot
 * interfere. A Redis instance must be available on the configured host and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=14")
class RateLimiterIT {

	private final FixedWindowRateLimiter fixedWindow;
	private final SlidingWindowLogRateLimiter slidingLog;
	private final SlidingWindowCounterRateLimiter slidingCounter;
	private final TokenBucketRateLimiter tokenBucket;
	private final StringRedisTemplate redisTemplate;

	@Autowired
	RateLimiterIT(
			FixedWindowRateLimiter fixedWindow,
			SlidingWindowLogRateLimiter slidingLog,
			SlidingWindowCounterRateLimiter slidingCounter,
			TokenBucketRateLimiter tokenBucket,
			StringRedisTemplate redisTemplate) {
		this.fixedWindow = fixedWindow;
		this.slidingLog = slidingLog;
		this.slidingCounter = slidingCounter;
		this.tokenBucket = tokenBucket;
		this.redisTemplate = redisTemplate;
	}

	@AfterEach
	void clearKeys() {
		Set<String> keys = redisTemplate.keys("rl:*");
		if (!keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}

	@Test
	void fixedWindowAllowsUpToLimitThenBlocks() {
		String key = uniqueKey();
		Duration window = Duration.ofSeconds(10);

		for (int i = 0; i < 5; i++) {
			assertThat(fixedWindow.tryAcquire(key, 5, window).allowed())
					.as("request %d within limit", i + 1)
					.isTrue();
		}

		RateLimitResult blocked = fixedWindow.tryAcquire(key, 5, window);
		assertThat(blocked.allowed()).isFalse();
		assertThat(blocked.remaining()).isZero();
		assertThat(blocked.retryAfterMillis()).isPositive();
	}

	@Test
	void fixedWindowResetsAfterWindowExpires() throws InterruptedException {
		String key = uniqueKey();
		Duration window = Duration.ofMillis(500);

		assertThat(fixedWindow.tryAcquire(key, 2, window).allowed()).isTrue();
		assertThat(fixedWindow.tryAcquire(key, 2, window).allowed()).isTrue();
		assertThat(fixedWindow.tryAcquire(key, 2, window).allowed()).isFalse();

		Thread.sleep(650); // let the window expire

		assertThat(fixedWindow.tryAcquire(key, 2, window).allowed())
				.as("allowed again in the next window")
				.isTrue();
	}

	@Test
	void slidingLogEnforcesExactLimit() {
		String key = uniqueKey();
		Duration window = Duration.ofSeconds(10);

		long allowed = countAllowed(slidingLog, key, 5, window, 8);
		assertThat(allowed).isEqualTo(5);
	}

	@Test
	void slidingCounterEnforcesLimit() {
		String key = uniqueKey();
		Duration window = Duration.ofSeconds(10);

		long allowed = countAllowed(slidingCounter, key, 5, window, 8);
		assertThat(allowed).isEqualTo(5);
	}

	@Test
	void tokenBucketAllowsBurstThenRefills() throws InterruptedException {
		String key = uniqueKey();
		Duration window = Duration.ofSeconds(5); // capacity 5 over 5s => 1 token/second

		long burst = countAllowed(tokenBucket, key, 5, window, 6);
		assertThat(burst).as("burst up to capacity").isEqualTo(5);

		assertThat(tokenBucket.tryAcquire(key, 5, window).allowed())
				.as("empty bucket rejects")
				.isFalse();

		Thread.sleep(1100); // ~1 token refilled

		assertThat(tokenBucket.tryAcquire(key, 5, window).allowed())
				.as("one token available after refill")
				.isTrue();
	}

	private static long countAllowed(
			RateLimiter limiter, String key, long limit, Duration window, int attempts) {
		long allowed = 0;
		for (int i = 0; i < attempts; i++) {
			if (limiter.tryAcquire(key, limit, window).allowed()) {
				allowed++;
			}
		}
		return allowed;
	}

	private static String uniqueKey() {
		return "client-" + UUID.randomUUID();
	}
}
