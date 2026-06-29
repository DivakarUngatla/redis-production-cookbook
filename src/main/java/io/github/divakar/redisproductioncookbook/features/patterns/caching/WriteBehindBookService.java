/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Write-behind (write-back): a write updates the cache immediately and is flushed to the
 * database asynchronously by a background task, giving very low write latency at the cost of a
 * window where the cache is ahead of the database (and data could be lost on a crash).
 */
@Service
public class WriteBehindBookService extends AbstractBookCache {

	private static final Logger log = LoggerFactory.getLogger(WriteBehindBookService.class);

	private final ConcurrentLinkedQueue<Book> pendingWrites = new ConcurrentLinkedQueue<>();

	public WriteBehindBookService(
			StringRedisTemplate redisTemplate, BookDatabase database, ObjectMapper objectMapper) {
		super(redisTemplate, database, objectMapper);
	}

	@Override
	public String name() {
		return "write-behind";
	}

	@Override
	public Optional<Book> get(String id) {
		Optional<Book> cached = readCache(id);
		if (cached.isPresent()) {
			return cached;
		}
		Optional<Book> fromDatabase = database.find(id);
		fromDatabase.ifPresent(book -> writeCache(book, DEFAULT_TTL));
		return fromDatabase;
	}

	@Override
	public Book update(Book book) {
		// Cache now (fast path); persist to the database later.
		writeCache(book, DEFAULT_TTL);
		pendingWrites.add(book);
		return book;
	}

	/**
	 * Drains queued writes to the database. Scheduling is enabled application-wide.
	 */
	@Scheduled(fixedDelay = 500L)
	void flushPendingWrites() {
		Book book;
		while ((book = pendingWrites.poll()) != null) {
			try {
				database.save(book);
			}
			catch (RuntimeException exception) {
				log.warn("Failed to flush book {}; re-queueing", book.id(), exception);
				pendingWrites.add(book);
				return;
			}
		}
	}

	/** @return number of writes still buffered in memory and not yet in the database */
	public int pendingCount() {
		return pendingWrites.size();
	}
}
