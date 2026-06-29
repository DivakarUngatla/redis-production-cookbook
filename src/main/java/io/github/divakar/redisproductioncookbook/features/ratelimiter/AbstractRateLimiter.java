/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * Shared plumbing for the Lua-backed rate limiters: running the script and turning its
 * {@code {allowed, remaining, retryAfterMillis}} reply into a {@link RateLimitResult}.
 */
public abstract class AbstractRateLimiter implements RateLimiter {

	protected final StringRedisTemplate redisTemplate;

	protected AbstractRateLimiter(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	/**
	 * Executes a rate-limit script that returns a three-element array
	 * {@code {allowed(0|1), remaining, retryAfterMillis}} and maps it to a result.
	 */
	protected RateLimitResult execute(
			RedisScript<List> script, List<String> keys, long limit, Object... args) {
		@SuppressWarnings("unchecked")
		List<Long> reply = redisTemplate.execute(script, keys, args);
		boolean allowed = reply.get(0) == 1L;
		long remaining = Math.max(0L, reply.get(1));
		long retryAfterMillis = Math.max(0L, reply.get(2));
		return new RateLimitResult(allowed, limit, remaining, retryAfterMillis);
	}
}
