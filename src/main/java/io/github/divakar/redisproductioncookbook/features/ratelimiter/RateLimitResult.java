/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.ratelimiter;

/**
 * The outcome of a single rate-limit check.
 *
 * @param allowed whether the request is admitted
 * @param limit the configured limit for the window
 * @param remaining how many more requests are allowed right now (0 when blocked)
 * @param retryAfterMillis when blocked, how long until a request would succeed (0 when allowed)
 */
public record RateLimitResult(boolean allowed, long limit, long remaining, long retryAfterMillis) {
}
