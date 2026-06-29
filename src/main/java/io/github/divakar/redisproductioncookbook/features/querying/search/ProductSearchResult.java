/*
 * Copyright (c) 2026 Divakar Ungatla
 */
package io.github.divakar.redisproductioncookbook.features.querying.search;

import java.math.BigDecimal;

/**
 * A single search hit: the product plus its BM25 relevance score.
 *
 * @param id unique product identifier
 * @param name product name
 * @param description product description
 * @param brand brand
 * @param category category
 * @param price price
 * @param score BM25 relevance score (higher is more relevant)
 */
public record ProductSearchResult(
		String id,
		String name,
		String description,
		String brand,
		String category,
		BigDecimal price,
		double score) {
}
