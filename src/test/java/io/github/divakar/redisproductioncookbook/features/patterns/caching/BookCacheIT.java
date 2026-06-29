/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for the caching strategies.
 *
 * <p>Runs against a dedicated Redis database and uses a unique book id per test, so cases
 * cannot interfere. Each test resets the database counters right before the action it
 * measures, so {@code databaseReads}/{@code databaseWrites} reflect only that action. A Redis
 * instance must be available on the configured host and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=13")
class BookCacheIT {

	private final CacheAsideBookService cacheAside;
	private final WriteThroughBookService writeThrough;
	private final WriteBehindBookService writeBehind;
	private final RefreshAheadBookService refreshAhead;
	private final StampedeProtectedBookService stampede;
	private final BookDatabase database;
	private final StringRedisTemplate redisTemplate;

	@Autowired
	BookCacheIT(
			CacheAsideBookService cacheAside,
			WriteThroughBookService writeThrough,
			WriteBehindBookService writeBehind,
			RefreshAheadBookService refreshAhead,
			StampedeProtectedBookService stampede,
			BookDatabase database,
			StringRedisTemplate redisTemplate) {
		this.cacheAside = cacheAside;
		this.writeThrough = writeThrough;
		this.writeBehind = writeBehind;
		this.refreshAhead = refreshAhead;
		this.stampede = stampede;
		this.database = database;
		this.redisTemplate = redisTemplate;
	}

	@BeforeEach
	void resetCounters() {
		database.resetCounters();
	}

	@AfterEach
	void clearCache() {
		redisTemplate.keys(AbstractBookCache.KEY_PREFIX + "*").forEach(redisTemplate::delete);
	}

	@Test
	void cacheAsideServesSecondReadFromCacheAndInvalidatesOnWrite() {
		Book book = sampleBook();
		database.save(book);
		database.resetCounters();

		assertThat(cacheAside.get(book.id())).contains(book);
		assertThat(database.readCount()).isEqualTo(1); // miss loaded from DB

		assertThat(cacheAside.get(book.id())).contains(book);
		assertThat(database.readCount()).isEqualTo(1); // second read served from cache

		Book updated = new Book(book.id(), "New Title", book.author(), book.price());
		cacheAside.update(updated); // writes DB and invalidates the cache

		assertThat(cacheAside.get(book.id())).contains(updated);
		assertThat(database.readCount()).isEqualTo(2); // invalidation forced a reload
	}

	@Test
	void writeThroughPopulatesCacheSoReadAfterWriteIsHit() {
		Book book = sampleBook();
		writeThrough.update(book); // writes DB and cache synchronously
		database.resetCounters();

		assertThat(writeThrough.get(book.id())).contains(book);
		assertThat(database.readCount()).isZero(); // already warm, no DB read
	}

	@Test
	void writeBehindCachesImmediatelyAndFlushesToDatabaseAsync() {
		Book book = sampleBook();
		database.resetCounters();

		writeBehind.update(book);

		// The value is readable from cache before the database has been written.
		assertThat(writeBehind.get(book.id())).contains(book);
		assertThat(database.readCount()).isZero();

		// The background flusher eventually persists it.
		await().atMost(Duration.ofSeconds(5))
				.untilAsserted(() -> assertThat(database.writeCount()).isEqualTo(1));
		assertThat(writeBehind.pendingCount()).isZero();
	}

	@Test
	void refreshAheadReloadsBeforeExpiryWhileStillServing() throws InterruptedException {
		Book book = sampleBook();
		refreshAhead.update(book); // TTL ~3s
		database.resetCounters();

		// Well before the refresh threshold: served from cache, no reload.
		assertThat(refreshAhead.get(book.id())).contains(book);
		assertThat(database.readCount()).isZero();

		// Sleep until the entry is inside the refresh window (pttl < 1.5s).
		Thread.sleep(1800);

		assertThat(refreshAhead.get(book.id())).contains(book); // still serves the cached value
		await().atMost(Duration.ofSeconds(3))
				.untilAsserted(() -> assertThat(database.readCount()).isGreaterThanOrEqualTo(1));
	}

	@Test
	void stampedeProtectionLoadsDatabaseOnceUnderConcurrentMisses() throws InterruptedException {
		Book book = sampleBook();
		database.save(book);
		database.resetCounters();

		int threads = 20;
		ExecutorService pool = Executors.newFixedThreadPool(threads);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(threads);
		List<Optional<Book>> results = Collections.synchronizedList(new ArrayList<>());

		for (int i = 0; i < threads; i++) {
			pool.execute(() -> {
				try {
					start.await();
					results.add(stampede.get(book.id()));
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

		assertThat(results).hasSize(threads).allMatch(result -> result.equals(Optional.of(book)));
		assertThat(database.readCount()).isEqualTo(1); // a single loader, no herd
	}

	private static Book sampleBook() {
		String id = UUID.randomUUID().toString();
		return new Book(id, "Redis in Action", "Carlson", new BigDecimal("39.99"));
	}
}
