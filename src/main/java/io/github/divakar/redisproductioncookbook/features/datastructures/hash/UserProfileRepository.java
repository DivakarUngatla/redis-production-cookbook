/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.hash;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * Persists {@link UserProfile} instances as Redis hashes.
 *
 * <p>Each profile is stored under a cluster-friendly key in the form
 * {@code user:profile:{id}}. Profiles may be saved persistently or with a
 * time-to-live.</p>
 */
@Repository
public class UserProfileRepository {

	private static final String KEY_PREFIX = "user:profile:";

	private static final String ID = "id";
	private static final String NAME = "name";
	private static final String EMAIL = "email";
	private static final String AGE = "age";

	private final HashOperations<String, String, String> hashes;

	public UserProfileRepository(StringRedisTemplate redisTemplate) {
		this.hashes = redisTemplate.opsForHash();
	}

	public UserProfile save(UserProfile profile) {
		hashes.putAll(key(profile.getId()), Map.of(
				ID, profile.getId(),
				NAME, profile.getName(),
				EMAIL, profile.getEmail(),
				AGE, Integer.toString(profile.getAge())));
		return profile;
	}

	public UserProfile save(UserProfile profile, Duration ttl) {
		if (ttl == null || ttl.isZero() || ttl.isNegative()) {
			throw new IllegalArgumentException("TTL must be greater than zero");
		}

		UserProfile saved = save(profile);
		Boolean expirationSet = hashes.getOperations().expire(key(profile.getId()), ttl);
		if (!Boolean.TRUE.equals(expirationSet)) {
			throw new IllegalStateException("Could not set TTL for user profile " + profile.getId());
		}
		return saved;
	}

	public Optional<UserProfile> findById(String id) {
		Map<String, String> fields = hashes.entries(key(id));
		
		if (fields.isEmpty()) {
			return Optional.empty();
		}

		return Optional.of(new UserProfile(
				fields.get(ID),
				fields.get(NAME),
				fields.get(EMAIL),
				Integer.parseInt(fields.get(AGE))));
	}

	public boolean deleteById(String id) {
		return Boolean.TRUE.equals(hashes.getOperations().delete(key(id)));
	}

	private String key(String id) {
		return KEY_PREFIX + "{" + id + "}";
	}

}
