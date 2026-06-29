/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.locks;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * A single-instance Redis distributed lock.
 *
 * <p>Acquire is an atomic {@code SET key token NX PX ttl}. The token is unique per holder, so
 * release and renew (run as atomic Lua scripts) only ever affect a lock the caller still
 * owns. {@link #executeWithLock} additionally runs a watchdog that renews the lease while the
 * guarded action is in progress.</p>
 *
 * <p>This is an efficiency lock, not an absolute correctness guarantee: failover and process
 * pauses can briefly produce two holders. See the module README for fencing tokens and the
 * Redlock discussion.</p>
 */
@Component
public class DistributedLock {

	static final String KEY_PREFIX = "lock:";

	/** Delete the key only if it still holds our token. */
	private static final RedisScript<Long> RELEASE_SCRIPT = RedisScript.of(
			"if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) "
					+ "else return 0 end",
			Long.class);

	/** Extend the TTL only if the key still holds our token. */
	private static final RedisScript<Long> RENEW_SCRIPT = RedisScript.of(
			"if redis.call('get', KEYS[1]) == ARGV[1] then "
					+ "return redis.call('pexpire', KEYS[1], ARGV[2]) else return 0 end",
			Long.class);

	private static final Logger log = LoggerFactory.getLogger(DistributedLock.class);

	private final StringRedisTemplate redisTemplate;
	private final ScheduledExecutorService watchdog =
			Executors.newScheduledThreadPool(1, runnable -> {
				Thread thread = new Thread(runnable, "lock-watchdog");
				thread.setDaemon(true);
				return thread;
			});

	public DistributedLock(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Attempts to acquire the lock without blocking.
	 *
	 * @param key the logical lock name
	 * @param ttl how long the lock should live before auto-expiring
	 * @return the owner token if acquired, otherwise empty
	 */
	public Optional<LockToken> tryAcquire(String key, Duration ttl) {
		String token = UUID.randomUUID().toString();
		Boolean acquired = redisTemplate.opsForValue().setIfAbsent(redisKey(key), token, ttl);
		return Boolean.TRUE.equals(acquired) ? Optional.of(new LockToken(key, token)) : Optional.empty();
	}

	/**
	 * Releases the lock, but only if it is still held by this token.
	 *
	 * @param token the owner token returned by {@link #tryAcquire}
	 * @return {@code true} if this call released the lock
	 */
	public boolean release(LockToken token) {
		Long released = redisTemplate.execute(
				RELEASE_SCRIPT, List.of(redisKey(token.key())), token.value());
		return Long.valueOf(1L).equals(released);
	}

	/**
	 * Extends the lock's lease, but only if it is still held by this token.
	 *
	 * @param token the owner token
	 * @param ttl the new time-to-live from now
	 * @return {@code true} if the lease was extended
	 */
	public boolean renew(LockToken token, Duration ttl) {
		Long renewed = redisTemplate.execute(
				RENEW_SCRIPT,
				List.of(redisKey(token.key())),
				token.value(),
				String.valueOf(ttl.toMillis()));
		return Long.valueOf(1L).equals(renewed);
	}

	/**
	 * Runs an action while holding the lock, renewing the lease via a watchdog until it
	 * completes, then releasing. Throws {@link LockNotAcquiredException} if the lock is held
	 * by someone else.
	 *
	 * @param key the logical lock name
	 * @param ttl the lease duration; the watchdog renews at roughly {@code ttl/3}
	 * @param action the work to run under the lock
	 * @param <T> the action's result type
	 * @return the action's result
	 */
	public <T> T executeWithLock(String key, Duration ttl, Supplier<T> action) {
		LockToken token = tryAcquire(key, ttl)
				.orElseThrow(() -> new LockNotAcquiredException(key));
		ScheduledFuture<?> renewal = scheduleRenewal(token, ttl);
		try {
			return action.get();
		}
		finally {
			renewal.cancel(false);
			release(token);
		}
	}

	/**
	 * Runs a no-result action while holding the lock.
	 *
	 * @param key the logical lock name
	 * @param ttl the lease duration
	 * @param action the work to run under the lock
	 */
	public void executeWithLock(String key, Duration ttl, Runnable action) {
		executeWithLock(key, ttl, () -> {
			action.run();
			return null;
		});
	}

	private ScheduledFuture<?> scheduleRenewal(LockToken token, Duration ttl) {
		long intervalMillis = Math.max(1L, ttl.toMillis() / 3);
		return watchdog.scheduleAtFixedRate(() -> {
			try {
				if (!renew(token, ttl)) {
					log.warn("Lost lock {} before renewal could extend it", token.key());
				}
			}
			catch (RuntimeException exception) {
				log.warn("Failed to renew lock {}", token.key(), exception);
			}
		}, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
	}

	private static String redisKey(String key) {
		return KEY_PREFIX + key;
	}

	@PreDestroy
	void shutdown() {
		watchdog.shutdownNow();
	}
}
