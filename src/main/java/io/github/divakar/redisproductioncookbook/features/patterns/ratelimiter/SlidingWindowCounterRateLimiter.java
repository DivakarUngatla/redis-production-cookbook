/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.ratelimiter;

import java.time.Duration;
import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window-counter rate limiter: keeps a counter for the current and previous fixed
 * windows and estimates the rolling count by weighting the previous window by how much of it
 * still overlaps. Smooths the fixed-window boundary burst with only two counters ({@code O(1)}),
 * at the cost of being an approximation. The usual production default.
 */
@Component
public class SlidingWindowCounterRateLimiter extends AbstractRateLimiter {

	private static final String KEY_PREFIX = "rl:swc:";

	private static final RedisScript<List> SCRIPT = RedisScript.of(
			"""
			local limit = tonumber(ARGV[1])
			local now = tonumber(ARGV[2])
			local window = tonumber(ARGV[3])
			local elapsed = now % window
			local weight = (window - elapsed) / window
			local curr = tonumber(redis.call('GET', KEYS[1]) or '0')
			local prev = tonumber(redis.call('GET', KEYS[2]) or '0')
			local estimated = prev * weight + curr
			if estimated < limit then
			  local newCount = redis.call('INCR', KEYS[1])
			  if newCount == 1 then redis.call('PEXPIRE', KEYS[1], window * 2) end
			  local remaining = math.floor(limit - estimated - 1)
			  if remaining < 0 then remaining = 0 end
			  return {1, remaining, 0}
			end
			return {0, 0, window - elapsed}
			""",
			List.class);

	public SlidingWindowCounterRateLimiter(StringRedisTemplate redisTemplate) {
		super(redisTemplate);
	}

	@Override
	public String name() {
		return "sliding-counter";
	}

	@Override
	public RateLimitResult tryAcquire(String key, long limit, Duration window) {
		long windowMillis = window.toMillis();
		long now = System.currentTimeMillis();
		long currentWindow = now / windowMillis;
		String currentKey = KEY_PREFIX + key + ":" + currentWindow;
		String previousKey = KEY_PREFIX + key + ":" + (currentWindow - 1);
		return execute(
				SCRIPT,
				List.of(currentKey, previousKey),
				limit,
				String.valueOf(limit),
				String.valueOf(now),
				String.valueOf(windowMillis));
	}
}
