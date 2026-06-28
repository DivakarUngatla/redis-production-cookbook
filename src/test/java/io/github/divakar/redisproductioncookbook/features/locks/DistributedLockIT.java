/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.locks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the Redis distributed lock.
 *
 * <p>Runs against a dedicated Redis database and uses a unique lock name per test, so cases
 * cannot interfere with each other. A Redis instance must be available on the configured host
 * and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=10")
class DistributedLockIT {

	private final DistributedLock lock;
	private final StringRedisTemplate redisTemplate;
	private final List<String> usedKeys = new ArrayList<>();

	@Autowired
	DistributedLockIT(DistributedLock lock, StringRedisTemplate redisTemplate) {
		this.lock = lock;
		this.redisTemplate = redisTemplate;
	}

	@AfterEach
	void cleanUp() {
		usedKeys.forEach(key -> redisTemplate.delete(DistributedLock.KEY_PREFIX + key));
		usedKeys.clear();
	}

	@Test
	void acquiredLockBlocksSecondAcquireUntilReleased() {
		String key = uniqueKey();

		Optional<LockToken> first = lock.tryAcquire(key, Duration.ofSeconds(30));
		assertThat(first).isPresent();

		// While held, no one else can acquire it.
		assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isEmpty();

		// After the owner releases, it becomes available again.
		assertThat(lock.release(first.get())).isTrue();
		assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isPresent();
	}

	@Test
	void releaseWithWrongTokenIsRefused() {
		String key = uniqueKey();
		LockToken owner = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();

		// A release that does not own the lock must not delete it.
		assertThat(lock.release(new LockToken(key, "not-the-owner"))).isFalse();
		assertThat(lock.tryAcquire(key, Duration.ofSeconds(30))).isEmpty();

		// The real owner can still release it.
		assertThat(lock.release(owner)).isTrue();
	}

	@Test
	void lockAutoExpiresAfterTtl() {
		String key = uniqueKey();
		assertThat(lock.tryAcquire(key, Duration.ofMillis(300))).isPresent();

		// Once the TTL elapses, the lock is gone and can be acquired again.
		await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
				assertThat(lock.tryAcquire(key, Duration.ofMillis(300))).isPresent());
	}

	@Test
	void watchdogKeepsLockAliveBeyondTtl() throws InterruptedException {
		String key = uniqueKey();
		Duration shortLease = Duration.ofMillis(500);
		CountDownLatch started = new CountDownLatch(1);

		Thread holder = new Thread(() -> lock.executeWithLock(key, shortLease, () -> {
			started.countDown();
			sleep(1500);
		}));
		holder.start();
		started.await();

		// Well past the 500ms lease: without the watchdog the lock would have expired, but the
		// renewal keeps it held for the whole 1500ms critical section.
		Thread.sleep(900);
		assertThat(lock.tryAcquire(key, shortLease)).isEmpty();

		holder.join();
		// Once the section finishes the lock is released.
		assertThat(lock.tryAcquire(key, shortLease)).isPresent();
	}

	@Test
	void executeWithLockThrowsWhenHeldAndRunsWhenFree() {
		String key = uniqueKey();
		LockToken owner = lock.tryAcquire(key, Duration.ofSeconds(30)).orElseThrow();

		assertThatExceptionOfType(LockNotAcquiredException.class)
				.isThrownBy(() -> lock.executeWithLock(key, Duration.ofSeconds(5), () -> "x"));

		assertThat(lock.release(owner)).isTrue();
		assertThat(lock.executeWithLock(key, Duration.ofSeconds(5), () -> "ran")).isEqualTo("ran");
	}

	@Test
	void concurrentExecuteWithLockNeverAllowsTwoHolders() throws InterruptedException {
		String key = uniqueKey();
		int threads = 8;
		AtomicInteger concurrent = new AtomicInteger();
		AtomicInteger maxConcurrent = new AtomicInteger();
		AtomicInteger successes = new AtomicInteger();
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);

		for (int i = 0; i < threads; i++) {
			pool.execute(() -> {
				try {
					start.await();
					lock.executeWithLock(key, Duration.ofSeconds(5), () -> {
						maxConcurrent.accumulateAndGet(concurrent.incrementAndGet(), Math::max);
						sleep(50);
						concurrent.decrementAndGet();
						successes.incrementAndGet();
						return null;
					});
				}
				catch (LockNotAcquiredException ignored) {
					// expected for the losers — the lock is non-blocking
				}
				catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
				}
				finally {
					done.countDown();
				}
			});
		}

		start.countDown();
		assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
		pool.shutdownNow();

		assertThat(maxConcurrent.get()).isEqualTo(1);
		assertThat(successes.get()).isGreaterThanOrEqualTo(1);
	}

	private String uniqueKey() {
		String key = "it-" + UUID.randomUUID();
		usedKeys.add(key);
		return key;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(interrupted);
		}
	}
}
