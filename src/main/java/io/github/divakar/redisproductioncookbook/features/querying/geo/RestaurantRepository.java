/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.geo;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Repository;

/**
 * Stores restaurant coordinates in the Redis Geo index {@code restaurants:locations}
 * and restaurant metadata in separate Redis hashes.
 *
 * <p>The Geo index member is the restaurant ID. Display metadata is stored in a
 * dedicated hash so a restaurant can be renamed without touching the geo index.</p>
 */
@Repository
public class RestaurantRepository {

	static final String GEO_KEY = "restaurants:locations";
	static final String RESTAURANT_META_PREFIX = "restaurant:meta:";

	private static final String ID_FIELD = "id";
	private static final String NAME_FIELD = "name";

	private final RedisTemplate<String, String> redisTemplate;
	private final GeoOperations<String, String> geoOperations;
	private final HashOperations<String, String, String> hashes;

	public RestaurantRepository(
			@Qualifier("geoRedisTemplate") RedisTemplate<String, String> redisTemplate) {
		this.redisTemplate = redisTemplate;
		this.geoOperations = redisTemplate.opsForGeo();
		this.hashes = redisTemplate.opsForHash();
	}

	/**
	 * Adds a restaurant to the geo index or replaces its coordinates if it already
	 * exists.
	 *
	 * @param id unique restaurant identifier
	 * @param name restaurant display name
	 * @param latitude WGS-84 latitude
	 * @param longitude WGS-84 longitude
	 * @return the stored restaurant
	 */
	public Restaurant addRestaurant(String id, String name, double latitude, double longitude) {
		validateRestaurant(id, name);
		validateCoordinates(longitude, latitude);

		geoOperations.add(GEO_KEY, new Point(longitude, latitude), id);
		hashes.putAll(metaKey(id), Map.of(ID_FIELD, id, NAME_FIELD, name));
		return new Restaurant(id, name, latitude, longitude);
	}

	/**
	 * Returns the stored location of a restaurant.
	 *
	 * @param id unique restaurant identifier
	 * @return the restaurant, or empty when the ID is not indexed
	 */
	public Optional<Restaurant> getLocation(String id) {
		List<Point> positions = geoOperations.position(GEO_KEY, id);
		if (positions == null || positions.isEmpty() || positions.get(0) == null) {
			return Optional.empty();
		}
		String name = hashes.get(metaKey(id), NAME_FIELD);
		if (name == null) {
			return Optional.empty();
		}
		Point point = positions.get(0);
		return Optional.of(new Restaurant(id, name, point.getY(), point.getX()));
	}

	/**
	 * Returns the distance between two indexed restaurants in kilometers.
	 *
	 * @param from first restaurant identifier
	 * @param to second restaurant identifier
	 * @return distance in kilometers, or empty when either restaurant is not indexed
	 */
	public Optional<Double> getDistance(String from, String to) {
		Distance distance = geoOperations.distance(GEO_KEY, from, to, Metrics.KILOMETERS);
		return Optional.ofNullable(distance).map(Distance::getValue);
	}

	/**
	 * Returns restaurant IDs within the given radius of a coordinate, ordered by
	 * distance ascending.
	 *
	 * @param longitude search origin longitude
	 * @param latitude search origin latitude
	 * @param radiusKm search radius in kilometers
	 * @return restaurant IDs in ascending distance order
	 */
	public List<String> findNearby(double longitude, double latitude, double radiusKm) {
		validateCoordinates(longitude, latitude);
		if (radiusKm <= 0) {
			throw new IllegalArgumentException("Radius must be greater than zero");
		}

		GeoResults<RedisGeoCommands.GeoLocation<String>> results = geoOperations.search(
				GEO_KEY,
				GeoReference.fromCoordinate(longitude, latitude),
				new Distance(radiusKm, Metrics.KILOMETERS),
				RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().sortAscending());

		if (results == null) {
			return List.of();
		}

		return results.getContent().stream()
				.map(result -> result.getContent().getName())
				.toList();
	}

	/**
	 * Removes a restaurant from the geo index and deletes its metadata.
	 *
	 * @param id unique restaurant identifier
	 * @return {@code true} when an indexed restaurant was removed
	 */
	public boolean removeRestaurant(String id) {
		Long removed = geoOperations.remove(GEO_KEY, id);
		redisTemplate.delete(metaKey(id));
		return removed != null && removed > 0;
	}

	private void validateRestaurant(String id, String name) {
		if (id == null || id.isBlank()) {
			throw new IllegalArgumentException("Restaurant ID must not be blank");
		}
		if (name == null || name.isBlank()) {
			throw new IllegalArgumentException("Restaurant name must not be blank");
		}
	}

	private void validateCoordinates(double longitude, double latitude) {
		if (longitude < -180 || longitude > 180) {
			throw new IllegalArgumentException("Longitude must be between -180 and 180");
		}
		if (latitude < -85.05112878 || latitude > 85.05112878) {
			throw new IllegalArgumentException("Latitude must be between -85.05112878 and 85.05112878");
		}
	}

	private String metaKey(String id) {
		return RESTAURANT_META_PREFIX + "{" + id + "}";
	}

}
