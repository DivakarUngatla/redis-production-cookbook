/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Exposes HTTP operations for writing and reading recent user activity feeds.
 */
@Validated
@RestController
@RequestMapping("/api/activity-feeds")
public class ActivityFeedController {

	private static final String USER_ID_PATTERN = "[a-zA-Z0-9_-]{1,128}";

	private final ActivityFeedRepository repository;

	/**
	 * Creates the activity feed controller.
	 *
	 * @param repository activity feed persistence component
	 */
	public ActivityFeedController(ActivityFeedRepository repository) {
		this.repository = repository;
	}

	/**
	 * Adds an event to the front of a user's recent activity feed.
	 *
	 * @param request validated activity request
	 * @return HTTP 201 with the created event
	 */
	@PostMapping("/events")
	public ResponseEntity<ActivityEvent> addActivity(
			@Valid @RequestBody CreateActivityEventRequest request) {
		ActivityEvent event = new ActivityEvent(
				UUID.randomUUID().toString(),
				request.userId(),
				request.eventType(),
				request.description(),
				Instant.now());
		repository.addActivity(event);

		URI location = URI.create("/api/activity-feeds/" + event.userId());
		return ResponseEntity.created(location).body(event);
	}

	/**
	 * Returns recent activities for a user in newest-first order.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @param limit maximum number of activities to return
	 * @return ordered recent activities
	 */
	@GetMapping("/{userId}")
	public List<ActivityEvent> getRecentActivities(
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId,
			@RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit) {
		return repository.getRecentActivities(userId, limit);
	}

	/**
	 * Returns the retained activity count for a user.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @return activity count
	 */
	@GetMapping("/{userId}/count")
	public ActivityCountResponse getActivityCount(
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId) {
		return new ActivityCountResponse(userId, repository.getActivityCount(userId));
	}

	/**
	 * Clears all retained activities for a user.
	 *
	 * @param userId identifier of the user who owns the feed
	 * @return HTTP 204 when cleared or HTTP 404 when no feed exists
	 */
	@DeleteMapping("/{userId}")
	public ResponseEntity<Void> clearHistory(
			@PathVariable @Pattern(regexp = USER_ID_PATTERN) String userId) {
		return repository.clearHistory(userId)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

}
