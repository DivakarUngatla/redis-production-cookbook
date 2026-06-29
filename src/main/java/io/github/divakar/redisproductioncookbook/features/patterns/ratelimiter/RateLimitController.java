/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.patterns.ratelimiter;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Demonstrates the rate-limiting algorithms. The {@code {algorithm}} path segment selects the
 * {@link RateLimiter}: {@code fixed-window}, {@code sliding-log}, {@code sliding-counter}, or
 * {@code token-bucket}; {@code {key}} is the client identity.
 *
 * <p>Returns 200 when the request is admitted and 429 Too Many Requests when it is throttled,
 * with {@code X-RateLimit-*} headers (and {@code Retry-After} when blocked).</p>
 */
@Validated
@RestController
@RequestMapping("/api/rate-limit")
public class RateLimitController {

	private final Map<String, RateLimiter> limiters;

	public RateLimitController(List<RateLimiter> limiters) {
		this.limiters = limiters.stream()
				.collect(Collectors.toMap(RateLimiter::name, Function.identity()));
	}

	@PostMapping("/{algorithm}/{key}")
	public ResponseEntity<RateLimitResult> tryAcquire(
			@PathVariable String algorithm,
			@PathVariable String key,
			@RequestParam(defaultValue = "10") @Min(1) @Max(1_000_000) long limit,
			@RequestParam(defaultValue = "60000") @Min(1) @Max(3_600_000) long windowMillis) {
		RateLimitResult result = limiter(algorithm).tryAcquire(key, limit, Duration.ofMillis(windowMillis));

		ResponseEntity.BodyBuilder response = ResponseEntity
				.status(result.allowed() ? HttpStatus.OK : HttpStatus.TOO_MANY_REQUESTS)
				.header("X-RateLimit-Limit", String.valueOf(result.limit()))
				.header("X-RateLimit-Remaining", String.valueOf(result.remaining()));
		if (!result.allowed()) {
			long retryAfterSeconds = (result.retryAfterMillis() + 999) / 1000;
			response.header("Retry-After", String.valueOf(retryAfterSeconds));
		}
		return response.body(result);
	}

	private RateLimiter limiter(String algorithm) {
		RateLimiter limiter = limiters.get(algorithm);
		if (limiter == null) {
			throw new ResponseStatusException(
					HttpStatus.NOT_FOUND, "Unknown rate-limit algorithm: " + algorithm);
		}
		return limiter;
	}
}
