/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.caching;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import io.github.divakar.redisproductioncookbook.features.locks.DistributedLock;
import io.github.divakar.redisproductioncookbook.features.locks.LockToken;

/**
 * Cache-aside with cache-stampede protection: on a miss, a single caller acquires a
 * distributed lock and loads from the database while the others briefly wait and then read
 * the now-warm cache, so a hot key's expiry produces one database load instead of a herd.
 */
@Service
public class StampedeProtectedBookService extends AbstractBookCache {

	static final Duration LOCK_TTL = Duration.ofSeconds(5);
	private static final int WAIT_ATTEMPTS = 50;
	private static final long WAIT_INTERVAL_MILLIS = 50L;

	private final DistributedLock distributedLock;

	public StampedeProtectedBookService(
			StringRedisTemplate redisTemplate,
			BookDatabase database,
			ObjectMapper objectMapper,
			DistributedLock distributedLock) {
		super(redisTemplate, database, objectMapper);
		this.distributedLock = distributedLock;
	}

	@Override
	public String name() {
		return "stampede";
	}

	@Override
	public Optional<Book> get(String id) {
		Optional<Book> cached = readCache(id);
		if (cached.isPresent()) {
			return cached;
		}
		return loadGuarded(id);
	}

	@Override
	public Book update(Book book) {
		database.save(book);
		evict(book.id());
		return book;
	}

	private Optional<Book> loadGuarded(String id) {
		String loadKey = "cacheload:book:" + id;
		Optional<LockToken> token = distributedLock.tryAcquire(loadKey, LOCK_TTL);
		if (token.isPresent()) {
			try {
				// Double-check: another caller may have populated the cache before we locked.
				Optional<Book> cached = readCache(id);
				if (cached.isPresent()) {
					return cached;
				}
				Optional<Book> fromDatabase = database.find(id);
				fromDatabase.ifPresent(book -> writeCache(book, DEFAULT_TTL));
				return fromDatabase;
			}
			finally {
				distributedLock.release(token.get());
			}
		}
		// Someone else is loading — wait for them to warm the cache instead of hitting the DB.
		return awaitWarmCache(id);
	}

	private Optional<Book> awaitWarmCache(String id) {
		for (int attempt = 0; attempt < WAIT_ATTEMPTS; attempt++) {
			sleep(WAIT_INTERVAL_MILLIS);
			Optional<Book> cached = readCache(id);
			if (cached.isPresent()) {
				return cached;
			}
		}
		// Loader did not populate (e.g. the book does not exist) — fall back to the database.
		return database.find(id);
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
