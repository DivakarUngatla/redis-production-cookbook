/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.geo;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.RedisTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Integration tests for the Redis-backed restaurant geo repository.
 *
 * <p>The tests use Redis database 15 and clear only the keys owned by this test
 * fixture. A Redis instance must be available on the configured host and port.</p>
 */
@SpringBootTest(properties = "spring.data.redis.database=15")
class RestaurantRepositoryIT {

	// Bangalore restaurants
	private static final String R101 = "geo-it-restaurant-101";
	private static final String R102 = "geo-it-restaurant-102";
	private static final String R103 = "geo-it-restaurant-103";

	private static final double R101_LON = 77.5946;
	private static final double R101_LAT = 12.9716;
	private static final double R102_LON = 77.6101;
	private static final double R102_LAT = 12.9352;
	private static final double R103_LON = 77.6410;
	private static final double R103_LAT = 12.9121;

	private final RestaurantRepository repository;
	private final RedisTemplate<String, String> redisTemplate;

	@Autowired
	RestaurantRepositoryIT(
			RestaurantRepository repository,
			@Qualifier("geoRedisTemplate") RedisTemplate<String, String> redisTemplate) {
		this.repository = repository;
		this.redisTemplate = redisTemplate;
	}

	@BeforeEach
	@AfterEach
	void cleanUp() {
		redisTemplate.delete(RestaurantRepository.GEO_KEY);
		redisTemplate.delete(List.of(
				metaKey(R101),
				metaKey(R102),
				metaKey(R103)));
	}

	@Test
	void addsAndRetrievesARestaurant() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);

		assertThat(repository.getLocation(R101))
				.isPresent()
				.get()
				.satisfies(r -> {
					assertThat(r.id()).isEqualTo(R101);
					assertThat(r.name()).isEqualTo("Biryani House");
					assertThat(r.latitude()).isCloseTo(R101_LAT, within(0.0001));
					assertThat(r.longitude()).isCloseTo(R101_LON, within(0.0001));
				});
	}

	@Test
	void returnsEmptyForUnknownRestaurant() {
		assertThat(repository.getLocation("unknown-restaurant")).isEmpty();
	}

	@Test
	void replacesCoordinatesOnDuplicateAdd() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);
		repository.addRestaurant(R101, "Biryani House Renamed", R102_LAT, R102_LON);

		assertThat(repository.getLocation(R101))
				.isPresent()
				.get()
				.satisfies(r -> {
					assertThat(r.name()).isEqualTo("Biryani House Renamed");
					assertThat(r.latitude()).isCloseTo(R102_LAT, within(0.0001));
					assertThat(r.longitude()).isCloseTo(R102_LON, within(0.0001));
				});
	}

	@Test
	void calculatesDistanceBetweenTwoRestaurants() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);
		repository.addRestaurant(R102, "Dosa Corner", R102_LAT, R102_LON);

		assertThat(repository.getDistance(R101, R102))
				.isPresent()
				.get()
				.satisfies(dist -> assertThat(dist).isCloseTo(4.5, within(1.0)));
	}

	@Test
	void returnsEmptyDistanceWhenRestaurantNotIndexed() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);

		assertThat(repository.getDistance(R101, "unknown-restaurant")).isEmpty();
	}

	@Test
	void findsNearbyRestaurantsOrderedByDistance() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);
		repository.addRestaurant(R102, "Dosa Corner", R102_LAT, R102_LON);
		repository.addRestaurant(R103, "Idli Palace", R103_LAT, R103_LON);

		List<String> nearby = repository.findNearby(R101_LON, R101_LAT, 5.0);

		assertThat(nearby).isNotEmpty();
		assertThat(nearby.get(0)).isEqualTo(R101);
		assertThat(nearby).contains(R101, R102);
	}

	@Test
	void returnsEmptyListWhenNoRestaurantsNearby() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);

		// Search from New York — nothing nearby
		List<String> nearby = repository.findNearby(-74.006, 40.7128, 1.0);

		assertThat(nearby).isEmpty();
	}

	@Test
	void removesRestaurantAndMetadata() {
		repository.addRestaurant(R101, "Biryani House", R101_LAT, R101_LON);

		assertThat(repository.removeRestaurant(R101)).isTrue();
		assertThat(repository.getLocation(R101)).isEmpty();
		assertThat(redisTemplate.hasKey(metaKey(R101))).isFalse();
		assertThat(repository.removeRestaurant(R101)).isFalse();
	}

	private String metaKey(String id) {
		return RestaurantRepository.RESTAURANT_META_PREFIX + "{" + id + "}";
	}

}
