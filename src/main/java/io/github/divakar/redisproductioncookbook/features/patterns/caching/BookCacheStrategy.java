/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.util.Optional;

/**
 * A caching strategy for {@link Book}s in front of the slow {@link BookDatabase}.
 *
 * <p>Each implementation exposes the same read/write surface but differs in how it keeps the
 * cache and the database in sync (cache-aside, write-through, write-behind, refresh-ahead, or
 * stampede-protected).</p>
 */
public interface BookCacheStrategy {

	/**
	 * @return the strategy name used in the API path (e.g. {@code aside})
	 */
	String name();

	/**
	 * Reads a book through this strategy's cache.
	 *
	 * @param id the book id
	 * @return the book if it exists in cache or the database
	 */
	Optional<Book> get(String id);

	/**
	 * Writes a book through this strategy.
	 *
	 * @param book the book to write
	 * @return the written book
	 */
	Book update(Book book);
}
