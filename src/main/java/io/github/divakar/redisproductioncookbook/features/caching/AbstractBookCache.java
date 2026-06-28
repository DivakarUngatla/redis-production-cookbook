/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.caching;

import java.time.Duration;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Shared cache plumbing for the {@link Book} caching strategies: key naming, JSON
 * serialization, and the basic cache read/write/evict operations against Redis.
 */
public abstract class AbstractBookCache implements BookCacheStrategy {

	static final String KEY_PREFIX = "cache:book:";
	static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

	protected final StringRedisTemplate redisTemplate;
	protected final BookDatabase database;
	protected final ObjectMapper objectMapper;

	protected AbstractBookCache(
			StringRedisTemplate redisTemplate, BookDatabase database, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.database = database;
		this.objectMapper = objectMapper;
	}

	protected static String cacheKey(String id) {
		return KEY_PREFIX + id;
	}

	protected Optional<Book> readCache(String id) {
		String json = redisTemplate.opsForValue().get(cacheKey(id));
		return json == null ? Optional.empty() : Optional.of(deserialize(json));
	}

	protected void writeCache(Book book, Duration ttl) {
		redisTemplate.opsForValue().set(cacheKey(book.id()), serialize(book), ttl);
	}

	protected void evict(String id) {
		redisTemplate.delete(cacheKey(id));
	}

	protected String serialize(Book book) {
		try {
			return objectMapper.writeValueAsString(book);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to serialize book " + book.id(), exception);
		}
	}

	protected Book deserialize(String json) {
		try {
			return objectMapper.readValue(json, Book.class);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Failed to deserialize cached book", exception);
		}
	}
}
