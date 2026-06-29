/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Write-through: every write updates the database and the cache synchronously, so reads after
 * a write are always cache hits and the cache never serves data older than the last write.
 */
@Service
public class WriteThroughBookService extends AbstractBookCache {

	public WriteThroughBookService(
			StringRedisTemplate redisTemplate, BookDatabase database, ObjectMapper objectMapper) {
		super(redisTemplate, database, objectMapper);
	}

	@Override
	public String name() {
		return "write-through";
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
		// Populate the cache in the same call, so the value is warm immediately after a write.
		writeCache(book, DEFAULT_TTL);
		return book;
	}
}
