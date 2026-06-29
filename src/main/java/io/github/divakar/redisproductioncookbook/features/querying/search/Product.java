/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.search;

import java.math.BigDecimal;

/**
 * A product stored as a Redis hash and indexed for full-text search.
 *
 * @param id unique product identifier (the hash key suffix)
 * @param name product name (highest search weight)
 * @param description product description (lower search weight)
 * @param brand brand, indexed as a tag for exact-match facets
 * @param category category, indexed as a tag for exact-match facets
 * @param price price, indexed as a sortable numeric for range filters
 */
public record Product(
		String id,
		String name,
		String description,
		String brand,
		String category,
		BigDecimal price) {
}
