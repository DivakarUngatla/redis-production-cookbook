/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.geo;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Describes a restaurant submitted through the restaurant API.
 *
 * @param id unique restaurant identifier
 * @param name restaurant display name
 * @param latitude WGS-84 latitude in decimal degrees, from -85.05112878 to 85.05112878
 * @param longitude WGS-84 longitude in decimal degrees, from -180.0 to 180.0
 */
public record AddRestaurantRequest(
		@NotBlank
		@Size(max = 128)
		@Pattern(regexp = "[a-zA-Z0-9_-]+", message = "must contain only letters, numbers, '_' or '-'")
		String id,
		@NotBlank @Size(max = 200) String name,
		@NotNull @DecimalMin("-85.05112878") @DecimalMax("85.05112878") Double latitude,
		@NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude) {
}
