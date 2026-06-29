/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.caching;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

/**
 * A deliberately slow, in-memory stand-in for a system of record (a database or remote API).
 *
 * <p>Every read and write sleeps to simulate I/O latency and increments a counter, so tests
 * and the {@code /stats} endpoint can show exactly how often the cache let callers avoid the
 * slow store. This is the thing the caching strategies sit in front of.</p>
 */
@Repository
public class BookDatabase {

	static final long READ_LATENCY_MILLIS = 200L;
	static final long WRITE_LATENCY_MILLIS = 200L;

	private static final Logger log = LoggerFactory.getLogger(BookDatabase.class);

	private final ConcurrentHashMap<String, Book> store = new ConcurrentHashMap<>();
	private final AtomicLong reads = new AtomicLong();
	private final AtomicLong writes = new AtomicLong();

	/**
	 * Reads a book, simulating slow I/O.
	 *
	 * @param id the book id
	 * @return the book if present
	 */
	public Optional<Book> find(String id) {
		reads.incrementAndGet();
		sleep(READ_LATENCY_MILLIS);
		log.info("DB read for book {}", id);
		return Optional.ofNullable(store.get(id));
	}

	/**
	 * Writes a book, simulating slow I/O.
	 *
	 * @param book the book to persist
	 * @return the persisted book
	 */
	public Book save(Book book) {
		writes.incrementAndGet();
		sleep(WRITE_LATENCY_MILLIS);
		store.put(book.id(), book);
		log.info("DB write for book {}", book.id());
		return book;
	}

	/** @return total simulated read operations since the last reset */
	public long readCount() {
		return reads.get();
	}

	/** @return total simulated write operations since the last reset */
	public long writeCount() {
		return writes.get();
	}

	/** Resets the read/write counters (useful for demos and tests). */
	public void resetCounters() {
		reads.set(0);
		writes.set(0);
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Simulated I/O interrupted", interrupted);
		}
	}
}
