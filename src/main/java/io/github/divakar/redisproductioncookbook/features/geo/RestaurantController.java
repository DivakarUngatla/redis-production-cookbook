/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.geo;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

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
 * Exposes HTTP operations for registering restaurants and performing location-based
 * searches.
 */
@Validated
@RestController
@RequestMapping("/api/restaurants")
public class RestaurantController {

	private static final String ID_PATTERN = "[a-zA-Z0-9_-]+";

	private final RestaurantRepository repository;

	public RestaurantController(RestaurantRepository repository) {
		this.repository = repository;
	}

	/**
	 * Registers a restaurant or replaces its coordinates if it already exists.
	 *
	 * @param request validated restaurant request
	 * @return the stored restaurant
	 */
	@PostMapping
	public ResponseEntity<Restaurant> addRestaurant(@Valid @RequestBody AddRestaurantRequest request) {
		Restaurant restaurant = repository.addRestaurant(
				request.id(), request.name(), request.latitude(), request.longitude());
		URI location = URI.create("/api/restaurants/" + request.id() + "/location");
		return ResponseEntity.created(location).body(restaurant);
	}

	/**
	 * Returns the stored location of a restaurant.
	 *
	 * @param id unique restaurant identifier
	 * @return the restaurant or HTTP 404 when not indexed
	 */
	@GetMapping("/{id}/location")
	public ResponseEntity<Restaurant> getLocation(
			@PathVariable @Pattern(regexp = ID_PATTERN) String id) {
		return repository.getLocation(id)
				.map(ResponseEntity::ok)
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Returns the distance in kilometers between two indexed restaurants.
	 *
	 * @param from first restaurant identifier
	 * @param to second restaurant identifier
	 * @return distance response or HTTP 404 when either restaurant is not indexed
	 */
	@GetMapping("/distance")
	public ResponseEntity<DistanceResponse> getDistance(
			@RequestParam @Pattern(regexp = ID_PATTERN) String from,
			@RequestParam @Pattern(regexp = ID_PATTERN) String to) {
		return repository.getDistance(from, to)
				.map(distanceKm -> ResponseEntity.ok(new DistanceResponse(from, to, distanceKm)))
				.orElseGet(() -> ResponseEntity.notFound().build());
	}

	/**
	 * Returns restaurant IDs within the given radius, ordered by distance.
	 *
	 * @param latitude search origin latitude
	 * @param longitude search origin longitude
	 * @param radiusKm search radius in kilometers
	 * @return restaurant IDs in ascending distance order
	 */
	@GetMapping("/nearby")
	public List<String> findNearby(
			@RequestParam @DecimalMin("-85.05112878") @DecimalMax("85.05112878") double latitude,
			@RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") double longitude,
			@RequestParam @Positive double radiusKm) {
		return repository.findNearby(longitude, latitude, radiusKm);
	}

	/**
	 * Removes a restaurant from the geo index.
	 *
	 * @param id unique restaurant identifier
	 * @return HTTP 204 when removed, or HTTP 404 when not indexed
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> removeRestaurant(
			@PathVariable @Pattern(regexp = ID_PATTERN) String id) {
		return repository.removeRestaurant(id)
				? ResponseEntity.noContent().build()
				: ResponseEntity.notFound().build();
	}

}
