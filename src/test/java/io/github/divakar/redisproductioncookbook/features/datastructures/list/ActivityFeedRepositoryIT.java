/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.datastructures.list;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for Redis List activity ordering and bounded retention.
 *
 * <p>The tests use Redis database 13. A Redis instance must be available on the
 * connection configured for the Spring Boot test context.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=13")
class ActivityFeedRepositoryIT {

	private static final String USER_ID = "activity-feed-it-user";

	private final ActivityFeedRepository repository;

	@Autowired
	ActivityFeedRepositoryIT(ActivityFeedRepository repository) {
		this.repository = repository;
	}

	@AfterEach
	void cleanUp() {
		repository.clearHistory(USER_ID);
	}

	@Test
	void addsAndRetrievesActivity() {
		ActivityEvent event = event("event-1", ActivityEventType.LOGIN, "User logged in", 1);

		repository.addActivity(event);

		assertThat(repository.getRecentActivities(USER_ID, 20)).containsExactly(event);
	}

	@Test
	void returnsActivitiesInNewestFirstInsertionOrder() {
		ActivityEvent first = event("event-1", ActivityEventType.LOGIN, "User logged in", 1);
		ActivityEvent second = event("event-2", ActivityEventType.PROFILE_UPDATED, "Profile updated", 2);
		ActivityEvent third = event("event-3", ActivityEventType.PURCHASE, "Order placed", 3);

		repository.addActivity(first);
		repository.addActivity(second);
		repository.addActivity(third);

		assertThat(repository.getRecentActivities(USER_ID, 20))
				.containsExactly(third, second, first);
	}

	@Test
	void returnsActivityCount() {
		repository.addActivity(event("event-1", ActivityEventType.LOGIN, "User logged in", 1));
		repository.addActivity(event("event-2", ActivityEventType.LOGOUT, "User logged out", 2));

		assertThat(repository.getActivityCount(USER_ID)).isEqualTo(2);
	}

	@Test
	void trimsHistoryToNewestEntries() {
		for (int sequence = 1; sequence <= 5; sequence++) {
			repository.addActivity(event(
					"event-" + sequence,
					ActivityEventType.PROFILE_UPDATED,
					"Update " + sequence,
					sequence));
		}

		repository.trimHistory(USER_ID, 2);

		List<ActivityEvent> activities = repository.getRecentActivities(USER_ID, 20);
		assertThat(activities).extracting(ActivityEvent::eventId)
				.containsExactly("event-5", "event-4");
		assertThat(repository.getActivityCount(USER_ID)).isEqualTo(2);
	}

	@Test
	void automaticallyKeepsOnlyOneHundredMostRecentActivities() {
		for (int sequence = 1; sequence <= 105; sequence++) {
			repository.addActivity(event(
					"event-" + sequence,
					ActivityEventType.PURCHASE,
					"Purchase " + sequence,
					sequence));
		}

		List<ActivityEvent> activities = repository.getRecentActivities(USER_ID, 100);
		assertThat(activities).hasSize(100);
		assertThat(activities.getFirst().eventId()).isEqualTo("event-105");
		assertThat(activities.getLast().eventId()).isEqualTo("event-6");
	}

	@Test
	void clearsHistory() {
		repository.addActivity(event("event-1", ActivityEventType.LOGIN, "User logged in", 1));

		assertThat(repository.clearHistory(USER_ID)).isTrue();
		assertThat(repository.getActivityCount(USER_ID)).isZero();
		assertThat(repository.getRecentActivities(USER_ID, 20)).isEmpty();
		assertThat(repository.clearHistory(USER_ID)).isFalse();
	}

	private ActivityEvent event(
			String eventId, ActivityEventType eventType, String description, long sequence) {
		return new ActivityEvent(
				eventId,
				USER_ID,
				eventType,
				description,
				Instant.parse("2026-01-01T00:00:00Z").plusSeconds(sequence));
	}

}
