/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * Sliding-window-log rate limiter: a sorted set holds one timestamp per request. Each call
 * evicts entries older than the window and admits if fewer than the limit remain. Exact (a true
 * rolling window with no boundary burst) but its memory grows with request volume.
 */
@Component
public class SlidingWindowLogRateLimiter extends AbstractRateLimiter {

	private static final String KEY_PREFIX = "rl:swl:";

	private static final RedisScript<List> SCRIPT = RedisScript.of(
			"""
			local limit = tonumber(ARGV[1])
			local now = tonumber(ARGV[2])
			local window = tonumber(ARGV[3])
			redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, now - window)
			local count = redis.call('ZCARD', KEYS[1])
			if count < limit then
			  redis.call('ZADD', KEYS[1], now, ARGV[4])
			  redis.call('PEXPIRE', KEYS[1], window)
			  return {1, limit - count - 1, 0}
			end
			local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
			local retry = window - (now - tonumber(oldest[2]))
			if retry < 0 then retry = 0 end
			return {0, 0, retry}
			""",
			List.class);

	public SlidingWindowLogRateLimiter(StringRedisTemplate redisTemplate) {
		super(redisTemplate);
	}

	@Override
	public String name() {
		return "sliding-log";
	}

	@Override
	public RateLimitResult tryAcquire(String key, long limit, Duration window) {
		long now = System.currentTimeMillis();
		String member = now + ":" + UUID.randomUUID();
		return execute(
				SCRIPT,
				List.of(KEY_PREFIX + key),
				limit,
				String.valueOf(limit),
				String.valueOf(now),
				String.valueOf(window.toMillis()),
				member);
	}
}
