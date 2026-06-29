/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.geo;

/**
 * Represents a restaurant and its geographic coordinates.
 *
 * @param id unique restaurant identifier
 * @param name restaurant display name
 * @param latitude WGS-84 latitude in decimal degrees
 * @param longitude WGS-84 longitude in decimal degrees
 */
public record Restaurant(String id, String name, double latitude, double longitude) {
}
