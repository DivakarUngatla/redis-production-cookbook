/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

import java.time.Duration;

/**
 * A rate-limiting algorithm backed by Redis. Each implementation makes its admit/reject
 * decision in a single atomic Lua script.
 */
public interface RateLimiter {

	/**
	 * @return the algorithm name used in the API path (e.g. {@code token-bucket})
	 */
	String name();

	/**
	 * Attempts to admit one request for the given client key.
	 *
	 * @param key the client identity (user id, API key, IP, ...)
	 * @param limit the maximum number of requests per window (the bucket capacity for token bucket)
	 * @param window the window duration over which the limit applies
	 * @return whether the request is allowed, plus remaining quota and retry timing
	 */
	RateLimitResult tryAcquire(String key, long limit, Duration window);
}
