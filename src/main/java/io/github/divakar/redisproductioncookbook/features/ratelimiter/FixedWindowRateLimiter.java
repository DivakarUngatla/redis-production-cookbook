/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Fixed-window rate limiter: a single counter per window, incremented with {@code INCR} and
 * expired after the window. Simple and {@code O(1)}, but allows a burst of up to twice the
 * limit straddling a window boundary.
 */
@Component
public class FixedWindowRateLimiter extends AbstractRateLimiter {

	private static final String KEY_PREFIX = "rl:fw:";

	private static final RedisScript<List> SCRIPT = RedisScript.of(
			"""
			local current = redis.call('INCR', KEYS[1])
			if current == 1 then
			  redis.call('PEXPIRE', KEYS[1], ARGV[2])
			end
			local limit = tonumber(ARGV[1])
			local ttl = redis.call('PTTL', KEYS[1])
			if ttl < 0 then ttl = tonumber(ARGV[2]) end
			if current > limit then
			  return {0, 0, ttl}
			end
			return {1, limit - current, 0}
			""",
			List.class);

	public FixedWindowRateLimiter(StringRedisTemplate redisTemplate) {
		super(redisTemplate);
	}

	@Override
	public String name() {
		return "fixed-window";
	}

	@Override
	public RateLimitResult tryAcquire(String key, long limit, Duration window) {
		return execute(
				SCRIPT,
				List.of(KEY_PREFIX + key),
				limit,
				String.valueOf(limit),
				String.valueOf(window.toMillis()));
	}
}
