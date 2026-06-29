/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Refresh-ahead: reads serve the cached value, but when an entry is close to expiring the
 * service asynchronously reloads it from the database and resets the TTL, so hot keys stay
 * warm and reads avoid the periodic miss spike at TTL boundaries.
 *
 * <p>Uses a short TTL and refresh threshold so the behaviour is easy to observe in a demo.</p>
 */
@Service
public class RefreshAheadBookService extends AbstractBookCache {

	static final Duration TTL = Duration.ofSeconds(3);
	static final Duration REFRESH_THRESHOLD = Duration.ofMillis(1500);

	private static final Logger log = LoggerFactory.getLogger(RefreshAheadBookService.class);

	private final ExecutorService refreshExecutor =
			Executors.newSingleThreadExecutor(runnable -> {
				Thread thread = new Thread(runnable, "cache-refresh-ahead");
				thread.setDaemon(true);
				return thread;
			});
	private final Set<String> refreshing = ConcurrentHashMap.newKeySet();

	public RefreshAheadBookService(
			StringRedisTemplate redisTemplate, BookDatabase database, ObjectMapper objectMapper) {
		super(redisTemplate, database, objectMapper);
	}

	@Override
	public String name() {
		return "refresh-ahead";
	}

	@Override
	public Optional<Book> get(String id) {
		String json = redisTemplate.opsForValue().get(cacheKey(id));
		if (json != null) {
			maybeRefresh(id);
			return Optional.of(deserialize(json));
		}
		Optional<Book> fromDatabase = database.find(id);
		fromDatabase.ifPresent(book -> writeCache(book, TTL));
		return fromDatabase;
	}

	@Override
	public Book update(Book book) {
		database.save(book);
		writeCache(book, TTL);
		return book;
	}

	private void maybeRefresh(String id) {
		Long pttl = redisTemplate.getExpire(cacheKey(id), TimeUnit.MILLISECONDS);
		if (pttl == null || pttl < 0 || pttl >= REFRESH_THRESHOLD.toMillis()) {
			return;
		}
		// Only one refresh per key in flight, so a burst of reads triggers a single reload.
		if (!refreshing.add(id)) {
			return;
		}
		refreshExecutor.execute(() -> {
			try {
				database.find(id).ifPresent(book -> writeCache(book, TTL));
			}
			catch (RuntimeException exception) {
				log.warn("Refresh-ahead reload failed for book {}", id, exception);
			}
			finally {
				refreshing.remove(id);
			}
		});
	}

	@PreDestroy
	void shutdown() {
		refreshExecutor.shutdownNow();
	}
}
