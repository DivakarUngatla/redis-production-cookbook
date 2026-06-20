/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.string;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Stores user sessions as JSON Redis Strings with mandatory expiration.
 *
 * <p>Each session uses a key in the form {@code session:{sessionId}}. Redis owns
 * expiration, so stale sessions are removed without an application cleanup job.</p>
 */
@Repository
public class UserSessionRepository {

	private static final String KEY_PREFIX = "session:";
	private static final int SESSION_ID_BYTES = 32;
	private static final SecureRandom SECURE_RANDOM = new SecureRandom();

	private static final DefaultRedisScript<Long> EXTEND_SESSION_SCRIPT =
			new DefaultRedisScript<>("""
					if redis.call('EXISTS', KEYS[1]) == 0 then
					    return 0
					end
					redis.call('SET', KEYS[1], ARGV[1])
					redis.call('EXPIRE', KEYS[1], ARGV[2])
					return 1
					""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Creates a session repository.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param objectMapper application JSON mapper
	 */
	public UserSessionRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Creates a new session with a secure random identifier and mandatory TTL.
	 *
	 * @param userId identifier of the user who owns the session
	 * @param ttl requested session lifetime
	 * @return the newly created session
	 */
	public UserSession createSession(String userId, Duration ttl) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("User ID must not be blank");
		}

		Duration normalizedTtl = normalizeTtl(ttl);
		Instant createdAt = Instant.now();
		UserSession session = new UserSession(
				generateSessionId(),
				userId,
				createdAt,
				createdAt.plus(normalizedTtl));

		redisTemplate.opsForValue().set(
				key(session.sessionId()), serialize(session), normalizedTtl);
		return session;
	}

	/**
	 * Retrieves a session when it exists and has not expired.
	 *
	 * @param sessionId session identifier
	 * @return the session, or an empty value when it is missing or expired
	 */
	public Optional<UserSession> getSession(String sessionId) {
		String json = redisTemplate.opsForValue().get(key(sessionId));
		return Optional.ofNullable(json).map(this::deserialize);
	}

	/**
	 * Renews a session from the current time while preserving its creation time.
	 *
	 * <p>The conditional update and expiration are executed atomically so a session
	 * deleted or expired during renewal is not recreated without a TTL.</p>
	 *
	 * @param sessionId session identifier
	 * @param ttl new session lifetime measured from now
	 * @return the renewed session, or an empty value when it no longer exists
	 */
	public Optional<UserSession> extendSession(String sessionId, Duration ttl) {
		Duration normalizedTtl = normalizeTtl(ttl);
		Optional<UserSession> existingSession = getSession(sessionId);
		if (existingSession.isEmpty()) {
			return Optional.empty();
		}

		UserSession existing = existingSession.orElseThrow();
		UserSession extended = new UserSession(
				existing.sessionId(),
				existing.userId(),
				existing.createdAt(),
				Instant.now().plus(normalizedTtl));

		Long updated = redisTemplate.execute(
				EXTEND_SESSION_SCRIPT,
				List.of(key(sessionId)),
				serialize(extended),
				Long.toString(normalizedTtl.toSeconds()));
		return updated != null && updated == 1L ? Optional.of(extended) : Optional.empty();
	}

	/**
	 * Deletes a session immediately.
	 *
	 * @param sessionId session identifier
	 * @return {@code true} when a session was deleted
	 */
	public boolean deleteSession(String sessionId) {
		return Boolean.TRUE.equals(redisTemplate.delete(key(sessionId)));
	}

	/**
	 * Checks whether a session currently exists.
	 *
	 * @param sessionId session identifier
	 * @return {@code true} when the session exists and has not expired
	 */
	public boolean sessionExists(String sessionId) {
		return Boolean.TRUE.equals(redisTemplate.hasKey(key(sessionId)));
	}

	/**
	 * Reads the remaining session lifetime with millisecond precision.
	 *
	 * @param sessionId session identifier
	 * @return remaining lifetime, or an empty value when missing or non-expiring
	 */
	public Optional<Duration> getRemainingTtl(String sessionId) {
		Long milliseconds = redisTemplate.getExpire(key(sessionId), TimeUnit.MILLISECONDS);
		if (milliseconds == null || milliseconds < 0) {
			return Optional.empty();
		}
		return Optional.of(Duration.ofMillis(milliseconds));
	}

	private Duration normalizeTtl(Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative() || ttl.toSeconds() < 1) {
			throw new IllegalArgumentException("TTL must be at least one second");
		}
		return Duration.ofSeconds(ttl.toSeconds());
	}

	private String generateSessionId() {
		byte[] bytes = new byte[SESSION_ID_BYTES];
		SECURE_RANDOM.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	private String serialize(UserSession session) {
		try {
			return objectMapper.writeValueAsString(session);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize user session", exception);
		}
	}

	private UserSession deserialize(String json) {
		try {
			return objectMapper.readValue(json, UserSession.class);
		}
		catch (JsonMappingException exception) {
			throw new IllegalStateException("Stored user session is invalid", exception);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not deserialize user session", exception);
		}
	}

	private String key(String sessionId) {
		if (sessionId == null || sessionId.isBlank()) {
			throw new IllegalArgumentException("Session ID must not be blank");
		}
		return KEY_PREFIX + "{" + sessionId + "}";
	}

}
