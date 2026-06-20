/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;

/**
 * Stores per-user recent activity feeds as bounded Redis Lists.
 *
 * <p>New events are inserted at the head of {@code activity:feed:{userId}}, so
 * range reads naturally return newest-first ordering. Each feed retains at most
 * 100 events.</p>
 */
@Repository
public class ActivityFeedRepository {

	static final int MAX_HISTORY_SIZE = 100;
	static final String KEY_PREFIX = "activity:feed:";

	private static final DefaultRedisScript<Long> ADD_ACTIVITY_SCRIPT =
			new DefaultRedisScript<>("""
					redis.call('LPUSH', KEYS[1], ARGV[1])
					redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
					return redis.call('LLEN', KEYS[1])
					""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final ObjectMapper objectMapper;

	/**
	 * Creates an activity feed repository.
	 *
	 * @param redisTemplate string-serialized Redis template
	 * @param objectMapper application JSON mapper
	 */
	public ActivityFeedRepository(StringRedisTemplate redisTemplate, ObjectMapper objectMapper) {
		this.redisTemplate = redisTemplate;
		this.objectMapper = objectMapper;
	}

	/**
	 * Adds an event to the front of its user's feed and atomically trims the feed to
	 * the most recent 100 entries.
	 *
	 * @param event activity to add
	 * @return the stored event
	 */
	public ActivityEvent addActivity(ActivityEvent event) {
		validateEvent(event);
		redisTemplate.execute(
				ADD_ACTIVITY_SCRIPT,
				List.of(key(event.userId())),
				serialize(event),
				Integer.toString(MAX_HISTORY_SIZE));
		return event;
	}

	/**
	 * Returns the newest activities retained for a user.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @param limit maximum number of activities to return, from 1 through 100
	 * @return activities in newest-first insertion order
	 */
	public List<ActivityEvent> getRecentActivities(String userId, int limit) {
		if (limit < 1 || limit > MAX_HISTORY_SIZE) {
			throw new IllegalArgumentException("Limit must be between 1 and 100");
		}

		List<String> values = redisTemplate.opsForList()
				.range(key(userId), 0, limit - 1L);
		if (values == null || values.isEmpty()) {
			return List.of();
		}
		return values.stream().map(this::deserialize).toList();
	}

	/**
	 * Returns the number of activities currently retained for a user.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @return retained activity count
	 */
	public long getActivityCount(String userId) {
		Long count = redisTemplate.opsForList().size(key(userId));
		return count == null ? 0 : count;
	}

	/**
	 * Keeps only the newest requested number of activities.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @param maxEntries maximum entries to retain, from 0 through 100
	 */
	public void trimHistory(String userId, int maxEntries) {
		if (maxEntries < 0 || maxEntries > MAX_HISTORY_SIZE) {
			throw new IllegalArgumentException("Maximum entries must be between 0 and 100");
		}
		if (maxEntries == 0) {
			clearHistory(userId);
			return;
		}
		redisTemplate.opsForList().trim(key(userId), 0, maxEntries - 1L);
	}

	/**
	 * Deletes all retained activity history for a user.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @return {@code true} when a feed was deleted
	 */
	public boolean clearHistory(String userId) {
		return Boolean.TRUE.equals(redisTemplate.delete(key(userId)));
	}

	private void validateEvent(ActivityEvent event) {
		Objects.requireNonNull(event, "Activity event must not be null");
		if (event.eventId() == null || event.eventId().isBlank()) {
			throw new IllegalArgumentException("Event ID must not be blank");
		}
		if (event.userId() == null || event.userId().isBlank()) {
			throw new IllegalArgumentException("User ID must not be blank");
		}
		Objects.requireNonNull(event.eventType(), "Event type must not be null");
		if (event.description() == null || event.description().isBlank()) {
			throw new IllegalArgumentException("Description must not be blank");
		}
		Objects.requireNonNull(event.createdAt(), "Created time must not be null");
	}

	private String serialize(ActivityEvent event) {
		try {
			return objectMapper.writeValueAsString(event);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not serialize activity event", exception);
		}
	}

	private ActivityEvent deserialize(String json) {
		try {
			return objectMapper.readValue(json, ActivityEvent.class);
		}
		catch (JsonMappingException exception) {
			throw new IllegalStateException("Stored activity event is invalid", exception);
		}
		catch (JsonProcessingException exception) {
			throw new IllegalStateException("Could not deserialize activity event", exception);
		}
	}

	private String key(String userId) {
		if (userId == null || userId.isBlank()) {
			throw new IllegalArgumentException("User ID must not be blank");
		}
		return KEY_PREFIX + "{" + userId + "}";
	}

}
