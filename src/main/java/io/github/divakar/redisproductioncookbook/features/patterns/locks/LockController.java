/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.locks;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Demonstrates the distributed lock: acquire, release, and a guarded critical section.
 */
@Validated
@RestController
@RequestMapping("/api/locks")
public class LockController {

	/** Base lease used by {@code /run}; the watchdog renews it for longer-running work. */
	private static final Duration RUN_LEASE = Duration.ofSeconds(2);

	private final DistributedLock distributedLock;

	public LockController(DistributedLock distributedLock) {
		this.distributedLock = distributedLock;
	}

	/**
	 * Attempts to acquire a lock without blocking.
	 *
	 * @param key the logical lock name
	 * @param ttlMillis lease duration in milliseconds
	 * @return whether the lock was acquired and, if so, the owner token
	 */
	@PostMapping("/{key}/acquire")
	public AcquireLockResponse acquire(
			@PathVariable String key,
			@RequestParam(defaultValue = "10000") @Min(100) @Max(600_000) long ttlMillis) {
		return distributedLock.tryAcquire(key, Duration.ofMillis(ttlMillis))
				.map(token -> new AcquireLockResponse(key, true, token.value(), ttlMillis))
				.orElseGet(() -> new AcquireLockResponse(key, false, null, ttlMillis));
	}

	/**
	 * Releases a lock, but only if the supplied token still owns it.
	 *
	 * @param key the logical lock name
	 * @param request the body carrying the owner token
	 * @return whether this call released the lock
	 */
	@PostMapping("/{key}/release")
	public ReleaseLockResponse release(
			@PathVariable String key, @Valid @RequestBody ReleaseLockRequest request) {
		boolean released = distributedLock.release(new LockToken(key, request.token()));
		return new ReleaseLockResponse(key, released);
	}

	/**
	 * Runs a guarded critical section that holds the lock for the requested time (the watchdog
	 * renews the lease as needed), then releases. A concurrent call while the lock is held
	 * returns 409 Conflict.
	 *
	 * @param key the logical lock name
	 * @param holdMillis how long the critical section should hold the lock
	 * @return 200 with the hold duration, or 409 if the lock was already held
	 */
	@PostMapping("/{key}/run")
	public ResponseEntity<RunUnderLockResponse> run(
			@PathVariable String key,
			@RequestParam(defaultValue = "1000") @Min(0) @Max(60_000) long holdMillis) {
		try {
			distributedLock.executeWithLock(key, RUN_LEASE, () -> sleep(holdMillis));
			return ResponseEntity.ok(new RunUnderLockResponse(key, true, holdMillis));
		}
		catch (LockNotAcquiredException alreadyHeld) {
			return ResponseEntity.status(HttpStatus.CONFLICT)
					.body(new RunUnderLockResponse(key, false, 0));
		}
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Critical section interrupted", interrupted);
		}
	}
}
