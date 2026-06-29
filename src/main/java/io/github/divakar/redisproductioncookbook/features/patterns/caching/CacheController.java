/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demonstrates the caching strategies against the slow {@link BookDatabase}. The {@code
 * {strategy}} path segment selects which {@link BookCacheStrategy} handles the request:
 * {@code aside}, {@code write-through}, {@code write-behind}, {@code refresh-ahead}, or
 * {@code stampede}.
 */
@Validated
@RestController
@RequestMapping("/api/cache")
public class CacheController {

	private final Map<String, BookCacheStrategy> strategies;
	private final BookDatabase database;

	public CacheController(List<BookCacheStrategy> strategies, BookDatabase database) {
		this.strategies = strategies.stream()
				.collect(Collectors.toMap(BookCacheStrategy::name, Function.identity()));
		this.database = database;
	}

	/**
	 * Reads a book through the chosen strategy. The first read is a slow database load; the
	 * next is a cache hit (watch {@code /stats}).
	 */
	@GetMapping("/{strategy}/books/{id}")
	public Book get(@PathVariable String strategy, @PathVariable String id) {
		return strategy(strategy).get(id)
				.orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Book not found"));
	}

	/**
	 * Writes a book through the chosen strategy (the write path differs per strategy).
	 */
	@PutMapping("/{strategy}/books/{id}")
	public Book update(
			@PathVariable String strategy,
			@PathVariable String id,
			@Valid @RequestBody BookRequest request) {
		return strategy(strategy).update(request.toBook(id));
	}

	/** @return how many times the slow database was actually touched */
	@GetMapping("/stats")
	public CacheStatsResponse stats() {
		return new CacheStatsResponse(database.readCount(), database.writeCount());
	}

	/** Resets the database counters (handy when demoing). */
	@PostMapping("/stats/reset")
	public ResponseEntity<Void> resetStats() {
		database.resetCounters();
		return ResponseEntity.noContent().build();
	}

	private BookCacheStrategy strategy(String name) {
		BookCacheStrategy strategy = strategies.get(name);
		if (strategy == null) {
			throw new ResponseStatusException(
					HttpStatus.NOT_FOUND, "Unknown caching strategy: " + name);
		}
		return strategy;
	}
}
