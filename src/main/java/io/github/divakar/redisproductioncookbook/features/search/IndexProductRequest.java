/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.search;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Describes a product submitted through the API for indexing.
 *
 * @param id unique product identifier
 * @param name product name
 * @param description product description (optional)
 * @param brand brand (optional)
 * @param category category (optional)
 * @param price price
 */
public record IndexProductRequest(
		@NotBlank @Size(max = 128) String id,
		@NotBlank @Size(max = 512) String name,
		@Size(max = 4000) String description,
		@Size(max = 128) String brand,
		@Size(max = 128) String category,
		@NotNull @PositiveOrZero BigDecimal price) {
}
