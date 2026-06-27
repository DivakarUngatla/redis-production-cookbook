/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.geo;

/**
 * API response containing the distance between two restaurants.
 *
 * @param from identifier of the first restaurant
 * @param to identifier of the second restaurant
 * @param distanceKm distance in kilometers
 */
public record DistanceResponse(String from, String to, double distanceKm) {
}
