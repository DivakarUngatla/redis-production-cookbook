/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.caching;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Cache-aside (lazy loading): read from cache, load from the database on a miss and populate
 * the cache; on write, update the database and invalidate the cache entry.
 */
@Service
public class CacheAsideBookService extends AbstractBookCache {

	public CacheAsideBookService(
			StringRedisTemplate redisTemplate, BookDatabase database, ObjectMapper objectMapper) {
		super(redisTemplate, database, objectMapper);
	}

	@Override
	public String name() {
		return "aside";
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
		database.save(book);
		// Invalidate rather than overwrite, so the next read reloads a guaranteed-fresh value.
		evict(book.id());
		return book;
	}
}
