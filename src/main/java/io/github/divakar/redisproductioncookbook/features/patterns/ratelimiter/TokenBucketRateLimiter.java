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
 * Token-bucket rate limiter: a bucket of up to {@code capacity} tokens refills at a steady
 * rate; each request consumes one token. Stored as a hash of {@code {tokens, ts}} and refilled
 * lazily from the time elapsed since the last request. Allows controlled bursts up to capacity
 * while bounding the sustained average rate — the most common production choice.
 */
@Component
public class TokenBucketRateLimiter extends AbstractRateLimiter {

	private static final String KEY_PREFIX = "rl:tb:";

	private static final RedisScript<List> SCRIPT = RedisScript.of(
			"""
			local capacity = tonumber(ARGV[1])
			local now = tonumber(ARGV[2])
			local window = tonumber(ARGV[3])
			local refillPerMs = capacity / window
			local data = redis.call('HMGET', KEYS[1], 'tokens', 'ts')
			local tokens = tonumber(data[1])
			local ts = tonumber(data[2])
			if tokens == nil then
			  tokens = capacity
			  ts = now
			end
			local elapsed = now - ts
			if elapsed < 0 then elapsed = 0 end
			tokens = math.min(capacity, tokens + elapsed * refillPerMs)
			local allowed = 0
			local retry = 0
			if tokens >= 1 then
			  tokens = tokens - 1
			  allowed = 1
			else
			  retry = math.ceil((1 - tokens) / refillPerMs)
			end
			redis.call('HSET', KEYS[1], 'tokens', tokens, 'ts', now)
			redis.call('PEXPIRE', KEYS[1], window * 2)
			return {allowed, math.floor(tokens), retry}
			""",
			List.class);

	public TokenBucketRateLimiter(StringRedisTemplate redisTemplate) {
		super(redisTemplate);
	}

	@Override
	public String name() {
		return "token-bucket";
	}

	@Override
	public RateLimitResult tryAcquire(String key, long limit, Duration window) {
		return execute(
				SCRIPT,
				List.of(KEY_PREFIX + key),
				limit,
				String.valueOf(limit),
				String.valueOf(System.currentTimeMillis()),
				String.valueOf(window.toMillis()));
	}
}
